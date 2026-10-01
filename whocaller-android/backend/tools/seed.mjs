#!/usr/bin/env node
// Uploads a caller dataset (CSV or JSON) to Firestore `callerNumbers`.
//
//   npm run seed -- data/my-callers.csv --region IN            # upload
//   npm run seed -- data/my-callers.csv --region IN --dry-run  # check the file only
//   npm run seed -- a.csv b.csv --max-writes 18000 --start 0    # several files; stay within a daily quota
//
// Several files: later files win for the same number. --max-writes stops early (the free Spark plan
// allows 20,000 writes a day) and prints the --start value to continue from next time.
//
// Columns (CSV header or JSON keys): phoneNumber (required), displayName, businessName,
// categories ("TELEMARKETING|BUSINESS_SERVICE"), spamScore (0-100), isVerified (true/false).
// Re-running is safe: existing numbers are updated, report counts are kept.
import { appendFileSync, readFileSync } from "node:fs";
import { extname } from "node:path";
import { FieldValue } from "firebase-admin/firestore";
import { CATEGORIES, NEUTRAL, args, combine, connect, normalize, parseCsv } from "./lib.mjs";

const opts = args(process.argv.slice(2));
const files = opts._;
if (files.length === 0) {
  console.error("Usage: npm run seed -- <file.csv|file.json>... [--region IN] [--dry-run] [--max-writes N] [--start N] [--key service-account.json]");
  process.exit(1);
}
const region = String(opts.region ?? process.env.DEFAULT_REGION ?? "IN").toUpperCase();
const dryRun = Boolean(opts["dry-run"]);

const rows = files.flatMap((file) => {
  const text = readFileSync(file, "utf8");
  const parsed = extname(file).toLowerCase() === ".json" ? JSON.parse(text) : parseCsv(text);
  if (!Array.isArray(parsed)) throw new Error(`${file}: JSON must be an array of objects.`);
  return parsed;
});
const maxWrites = opts["max-writes"] ? Number(opts["max-writes"]) : Infinity;
const start = Number(opts.start ?? 0);

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
let empty = 0;
rows.forEach((row, i) => {
  const line = i + 2; // header is line 1
  const rawNumber = String(row.phoneNumber ?? row.number ?? row.phone ?? "").trim();
  if (!rawNumber) { empty++; return; } // e.g. template rows not filled in yet
  const n = normalize(rawNumber, region);
  if (!n) return problems.push(`line ${line}: not a valid phone number (${row.phoneNumber ?? ""})`);
  const rawCats = Array.isArray(row.categories) ? row.categories : String(row.categories ?? "").split(/[|;]/);
  const cats = [];
  for (const c of rawCats.filter((c) => String(c).trim())) {
    const k = category(c);
    if (!k) return problems.push(`line ${line}: unknown category "${c}" (use: ${CATEGORIES.join(", ")})`);
    if (!cats.includes(k)) cats.push(k);
  }
  // Blank cells leave what Firestore already has (so several sources can be combined).
  let score;
  if (String(row.spamScore ?? "").trim() !== "") {
    score = Number(row.spamScore);
    if (!Number.isFinite(score) || score < 0 || score > 100) return problems.push(`line ${line}: spamScore must be 0-100`);
  } else if (cats.length) {
    score = cats.some((c) => !NEUTRAL.has(c)) ? 70 : 0;
  }
  const verifiedCell = String(row.isVerified ?? "").trim();
  const verified = verifiedCell === "" ? undefined : /^(true|yes|1)$/i.test(verifiedCell);
  records.set(n.e164, {
    phoneNumber: n.display,
    normalizedNumber: n.e164,
    countryCode: n.countryCode,
    region: n.region,
    displayName: clean(row.displayName ?? row.name, 80),
    businessName: clean(row.businessName, 80),
    isVerified: verified,
    seedSpamScore: score === undefined ? undefined : Math.round(score),
    seedCategories: cats.length ? cats.slice(0, 3) : undefined,
  });
});

console.log(`${rows.length} rows read, ${records.size} valid numbers, ${empty} without a number, ${problems.length} problems.`);
problems.slice(0, 50).forEach((p) => console.log("  - " + p));
if (problems.length > 50) console.log(`  … ${problems.length - 50} more`);
if (dryRun) {
  console.log("Dry run: nothing uploaded.");
  process.exit(problems.length ? 2 : 0);
}

const db = connect(opts.key);
const col = db.collection("callerNumbers");
const all = [...records.values()];
const entries = all.slice(start, start + maxWrites);
if (start > 0) console.log(`Starting at number ${start}.`);
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
      isVerified: merged.isVerified ?? false,
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
const next = start + entries.length;
if (next < all.length) {
  console.log(`Stopped after ${entries.length} numbers (write limit). Continue another day with: --start ${next}`);
  if (process.env.GITHUB_OUTPUT) appendFileSync(process.env.GITHUB_OUTPUT, `next_start=${next}\n`);
} else {
  console.log("Done: all numbers uploaded.");
}
