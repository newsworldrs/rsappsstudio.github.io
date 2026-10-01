#!/usr/bin/env node
// Recomputes every callerNumbers summary (spamScore, totalReports, categories…) from `reports`.
// Use this on the free Spark plan (no Cloud Functions), e.g. nightly from GitHub Actions:
//
//   npm run aggregate                 # all numbers that have reports
//   npm run aggregate -- --number +919876543210
//
// With Cloud Functions deployed (Blaze plan) the `onReportWritten` trigger does this per report.
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { args, combine, connect, normalize, summarize } from "./lib.mjs";

const opts = args(process.argv.slice(2));
const db = connect(opts.key);
const now = Date.now();

const byNumber = new Map();
let query = db.collection("reports");
if (opts.number) {
  const n = normalize(opts.number, String(opts.region ?? "IN"));
  if (!n) throw new Error("Not a valid number: " + opts.number);
  query = query.where("phoneNumber", "==", n.e164);
}
const snap = await query.get();
snap.forEach((d) => {
  const r = d.data();
  if (!r.phoneNumber) return;
  if (!byNumber.has(r.phoneNumber)) byNumber.set(r.phoneNumber, []);
  byNumber.get(r.phoneNumber).push(r);
});
console.log(`${snap.size} reports for ${byNumber.size} numbers.`);

const col = db.collection("callerNumbers");
const numbers = [...byNumber.keys()];
for (let i = 0; i < numbers.length; i += 300) {
  const chunk = numbers.slice(i, i + 300);
  const refs = chunk.map((n) => col.doc(n));
  const existing = await db.getAll(...refs);
  const batch = db.batch();
  chunk.forEach((e164, j) => {
    const before = existing[j].exists ? existing[j].data() : {};
    const s = summarize(byNumber.get(e164), now);
    const merged = { ...before, ...s };
    const { spamScore, categories, externalActive } = combine(merged);
    const n = normalize(e164);
    batch.set(refs[j], {
      phoneNumber: before.phoneNumber ?? n?.display ?? e164,
      normalizedNumber: e164,
      countryCode: before.countryCode ?? n?.countryCode ?? null,
      region: before.region ?? n?.region ?? null,
      isVerified: before.isVerified ?? false,
      totalReports: s.totalReports,
      categoryCounts: s.categoryCounts,
      topCategories: s.topCategories,
      reportSpamScore: s.reportSpamScore,
      reportsLast24h: s.reportsLast24h,
      reportsLast7d: s.reportsLast7d,
      lastReportedAt: s.lastReportedAt ? Timestamp.fromMillis(s.lastReportedAt) : null,
      spamScore,
      categories,
      externalActive,
      source: before.seedSpamScore !== undefined ? "seed+reports" : "reports",
      updatedAt: FieldValue.serverTimestamp(),
      ...(existing[j].exists ? {} : { createdAt: FieldValue.serverTimestamp() }),
    }, { merge: true });
  });
  await batch.commit();
  console.log(`Updated ${Math.min(i + 300, numbers.length)}/${numbers.length}`);
}
console.log("Done.");
