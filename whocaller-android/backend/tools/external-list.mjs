#!/usr/bin/env node
// Outside spam lists: numbers flagged by a third-party list, kept apart from WhoCaller's own data.
//
//   node external-list.mjs import data/list.json --list rl-2026-09 --name "Public spam list" --score 60 [--dry-run]
//   node external-list.mjs remove --list rl-2026-09            # takes the whole list back out
//
// How it's kept safe:
//  - Stored in its own fields (externalList, externalSpamScore, …), never as WhoCaller reports.
//  - The app labels these "Possible spam · flagged by an outside list", not plain "Spam".
//  - Two WhoCaller users answering "Not spam" switch the label off for everyone (see lib.mjs combine).
//  - Numbers WhoCaller already knows as a business, bank, helpline or named caller are skipped.
//  - `remove` deletes numbers that only came from the list and strips the list from the others.
// Input: JSON array of {number|phoneNumber, details?} or a CSV with a phoneNumber column.
import { readFileSync } from "node:fs";
import { extname } from "node:path";
import { FieldValue } from "firebase-admin/firestore";
import { args, combine, connect, normalize, parseCsv, strictNumber } from "./lib.mjs";

const opts = args(process.argv.slice(2));
const [command, file] = opts._;
const list = String(opts.list ?? "").trim();
if (!["import", "remove"].includes(command) || !/^[a-z0-9-]{3,40}$/.test(list) || (command === "import" && !file)) {
  console.error('Usage: node external-list.mjs import <file> --list <id> --name "<label>" [--score 60] [--dry-run]\n       node external-list.mjs remove --list <id>');
  process.exit(1);
}
const EXTERNAL_FIELDS = ["externalList", "externalListName", "externalSpamScore", "externalCategories", "externalAddedAt"];

if (command === "import") {
  const score = Number(opts.score ?? 60);
  if (!Number.isFinite(score) || score < 1 || score > 80) throw new Error("--score must be 1-80 (outside lists never count as certain)");
  const name = String(opts.name ?? "Outside spam list").slice(0, 60);
  const text = readFileSync(file, "utf8");
  const rows = extname(file).toLowerCase() === ".json" ? JSON.parse(text) : parseCsv(text);
  const numbers = new Map();
  let invalid = 0;
  let examples = 0;
  for (const row of rows) {
    const details = String(row.details ?? "");
    if (/example entry/i.test(details)) { examples++; continue; }
    const n = strictNumber(row.number ?? row.phoneNumber ?? row.phone_number, "IN");
    if (!n) { invalid++; continue; }
    numbers.set(n.e164, n);
  }
  console.log(`${rows.length} rows: ${numbers.size} valid numbers, ${invalid} invalid, ${examples} example rows skipped.`);
  if (opts["dry-run"]) process.exit(0);

  const db = connect(opts.key);
  const col = db.collection("callerNumbers");
  const all = [...numbers.keys()];
  let added = 0;
  let skippedKnown = 0;
  for (let i = 0; i < all.length; i += 300) {
    const chunk = all.slice(i, i + 300);
    const refs = chunk.map((e164) => col.doc(e164));
    const existing = await db.getAll(...refs);
    const batch = db.batch();
    chunk.forEach((e164, j) => {
      const before = existing[j].exists ? existing[j].data() : null;
      // Known legitimate identity (dataset business/bank/helpline, or a named caller): leave it alone.
      if (before && (before.isVerified || before.businessName || before.displayName || before.seedSpamScore !== undefined)) {
        skippedKnown++;
        return;
      }
      const n = normalize(e164, "IN");
      const external = {
        externalList: list,
        externalListName: name,
        externalSpamScore: Math.round(score),
        externalCategories: ["SPAM"],
      };
      const { spamScore, categories, externalActive } = combine({ ...(before ?? {}), ...external });
      batch.set(refs[j], {
        ...external,
        externalAddedAt: FieldValue.serverTimestamp(),
        phoneNumber: before?.phoneNumber ?? n.display,
        normalizedNumber: e164,
        countryCode: before?.countryCode ?? n.countryCode,
        region: before?.region ?? n.region,
        isVerified: false,
        totalReports: before?.totalReports ?? 0,
        spamScore,
        categories,
        externalActive,
        source: before?.totalReports ? "external+reports" : "external",
        updatedAt: FieldValue.serverTimestamp(),
        ...(before ? {} : { createdAt: FieldValue.serverTimestamp() }),
      }, { merge: true });
      added++;
    });
    await batch.commit();
    console.log(`Processed ${Math.min(i + 300, all.length)}/${all.length}`);
  }
  console.log(`Done: ${added} numbers flagged from "${name}" (${list}), ${skippedKnown} skipped because WhoCaller already knows them.`);
} else {
  const db = connect(opts.key);
  const snap = await db.collection("callerNumbers").where("externalList", "==", list).get();
  let deleted = 0;
  let stripped = 0;
  for (let i = 0; i < snap.docs.length; i += 300) {
    const batch = db.batch();
    for (const doc of snap.docs.slice(i, i + 300)) {
      const d = doc.data();
      const onlyFromList = !d.totalReports && d.seedSpamScore === undefined && !d.displayName && !d.businessName && !d.isVerified;
      if (onlyFromList) {
        batch.delete(doc.ref);
        deleted++;
      } else {
        const rest = { ...d };
        EXTERNAL_FIELDS.forEach((f) => delete rest[f]);
        const { spamScore, categories, externalActive } = combine(rest);
        batch.set(doc.ref, {
          ...Object.fromEntries(EXTERNAL_FIELDS.map((f) => [f, FieldValue.delete()])),
          spamScore,
          categories,
          externalActive,
          source: d.seedSpamScore !== undefined ? (d.totalReports ? "seed+reports" : "seed") : "reports",
          updatedAt: FieldValue.serverTimestamp(),
        }, { merge: true });
        stripped++;
      }
    }
    await batch.commit();
  }
  console.log(`Removed list ${list}: ${deleted} numbers deleted, ${stripped} kept (they have WhoCaller reports or data) with the list taken off.`);
}
