#!/usr/bin/env node
// Uploads a caller dataset (CSV or JSON) to Firestore `callerNumbers`.
//
//   npm run seed -- data/my-callers.csv --region IN            # upload
//   npm run seed -- data/my-callers.csv --region IN --dry-run  # check the file only
//
// Columns (CSV header or JSON keys): phoneNumber (required), displayName, businessName,
// categories ("TELEMARKETING|BUSINESS_SERVICE"), spamScore (0-100), isVerified (true/false).
// Re-running is safe: existing numbers are updated, report counts are kept.
import { readFileSync } from "node:fs";
import { extname } from "node:path";
import { FieldValue } from "firebase-admin/firestore";
import { CATEGORIES, NEUTRAL, args, combine, connect, normalize, parseCsv } from "./lib.mjs";

const opts = args(process.argv.slice(2));
const file = opts._[0];
if (!file) {
  console.error("Usage: npm run seed -- <file.csv|file.json> [--region IN] [--dry-run] [--key service-account.json]");
  process.exit(1);
}
const region = String(opts.region ?? process.env.DEFAULT_REGION ?? "IN").toUpperCase();
const dryRun = Boolean(opts["dry-run"]);

const text = readFileSync(file, "utf8");
const rows = extname(file).toLowerCase() === ".json" ? JSON.parse(text) : parseCsv(text);
if (!Array.isArray(rows)) throw new Error("JSON must be an array of objects.");

/** "Business / Service" → BUSINESS_SERVICE; unknown names are rejected. */
function category(name) {
  const key = String(name).trim().toUpperCase().replace(/[^A-Z0-9]+/g, "_").replace(/^_|_$/g, "");
  return CATEGORIES.includes(key) ? key : null;
}

const clean = (v, max) => {
  const s = String(v ?? "").trim();
  return s ? s.slice(0, max) : undefined;
};

const records = new Map();
const problems = [];
rows.forEach((row, i) => {
  const line = i + 2; // header is line 1
  const n = normalize(row.phoneNumber ?? row.number ?? row.phone, region);
  if (!n) return problems.push(`line ${line}: not a valid phone number (${row.phoneNumber ?? ""})`);
  const rawCats = Array.isArray(row.categories) ? row.categories : String(row.categories ?? "").split(/[|;]/);
  const cats = [];
  for (const c of rawCats.filter((c) => String(c).trim())) {
    const k = category(c);
    if (!k) return problems.push(`line ${line}: unknown category "${c}" (use: ${CATEGORIES.join(", ")})`);
    if (!cats.includes(k)) cats.push(k);
  }
  let score;
  if (String(row.spamScore ?? "").trim() !== "") {
    score = Number(row.spamScore);
    if (!Number.isFinite(score) || score < 0 || score > 100) return problems.push(`line ${line}: spamScore must be 0-100`);
  } else {
    score = cats.some((c) => !NEUTRAL.has(c)) ? 70 : 0;
  }
  const verified = /^(true|yes|1)$/i.test(String(row.isVerified ?? "").trim());
  records.set(n.e164, {
    phoneNumber: n.display,
    normalizedNumber: n.e164,
    countryCode: n.countryCode,
    region: n.region,
    displayName: clean(row.displayName ?? row.name, 80),
    businessName: clean(row.businessName, 80),
    isVerified: verified,
    seedSpamScore: Math.round(score),
    seedCategories: cats.slice(0, 3),
  });
});

console.log(`${rows.length} rows read, ${records.size} valid numbers, ${problems.length} problems.`);
problems.slice(0, 50).forEach((p) => console.log("  - " + p));
if (problems.length > 50) console.log(`  … ${problems.length - 50} more`);
if (dryRun) {
  console.log("Dry run: nothing uploaded.");
  process.exit(problems.length ? 2 : 0);
}

const db = connect(opts.key);
const col = db.collection("callerNumbers");
const entries = [...records.values()];
let written = 0;
for (let i = 0; i < entries.length; i += 300) {
  const chunk = entries.slice(i, i + 300);
  const refs = chunk.map((r) => col.doc(r.normalizedNumber));
  const existing = await db.getAll(...refs);
  const batch = db.batch();
  chunk.forEach((record, j) => {
    const before = existing[j].exists ? existing[j].data() : {};
    // Leave fields the file doesn't mention as they are.
    const update = Object.fromEntries(Object.entries(record).filter(([, v]) => v !== undefined));
    const merged = { ...before, ...update };
    const { spamScore, categories } = combine(merged);
    batch.set(refs[j], {
      ...update,
      spamScore,
      categories,
      totalReports: before.totalReports ?? 0,
      source: before.totalReports ? "seed+reports" : "seed",
      updatedAt: FieldValue.serverTimestamp(),
      ...(existing[j].exists ? {} : { createdAt: FieldValue.serverTimestamp() }),
    }, { merge: true });
  });
  await batch.commit();
  written += chunk.length;
  console.log(`Uploaded ${written}/${entries.length}`);
}
console.log("Done.");
