/**
 * Keeps callerNumbers/{E.164} in sync with reports/{digits}_{uid} written directly by the app.
 * Same rules as backend/tools/lib.mjs (used by `npm run aggregate` on the free plan).
 * Deploying this trigger needs the Blaze plan; without it, run the aggregate tool instead.
 */
import { FieldValue, Timestamp, getFirestore } from "firebase-admin/firestore";
import { onDocumentWritten } from "firebase-functions/v2/firestore";

const CATEGORIES = [
  "SPAM", "TELEMARKETING", "FINANCIAL_SCAM", "FRAUD_FAKE_OFFER", "IMPERSONATION", "BANKING_SCAM",
  "BUSINESS_SERVICE", "DELIVERY", "ROBOCALL", "HARASSMENT", "OTHER",
];
const NEUTRAL = new Set(["BUSINESS_SERVICE", "DELIVERY"]);
const DAY_MS = 86_400_000;
const log2 = (x: number) => Math.log(x) / Math.LN2;

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Doc = Record<string, any>;

export function summarize(reports: Doc[], now: number) {
  const counts: Record<string, number> = {};
  let unwanted = 0;
  let last24 = 0;
  let last7 = 0;
  let lastReportedAt = 0;
  for (const r of reports) {
    const cats: string[] = (r.categories ?? []).filter((c: string) => CATEGORIES.includes(c)).slice(0, 2);
    if (cats.length === 0) continue;
    for (const c of cats) counts[c] = (counts[c] ?? 0) + 1;
    const at: number = r.reportedAt?.toMillis?.() ?? 0;
    lastReportedAt = Math.max(lastReportedAt, at);
    if (cats.some((c) => !NEUTRAL.has(c))) {
      unwanted++;
      if (now - at < DAY_MS) last24++;
      if (now - at < 7 * DAY_MS) last7++;
    }
  }
  let score = unwanted === 0 ? 0
    : Math.min(55, 13 * log2(1 + unwanted)) + Math.min(15, 5 * log2(1 + last24)) + Math.min(10, 3 * log2(1 + last7));
  if (lastReportedAt) {
    const ageDays = (now - lastReportedAt) / DAY_MS;
    score -= ageDays > 365 ? 20 : ageDays > 180 ? 10 : ageDays > 90 ? 5 : 0;
  }
  return {
    totalReports: reports.length,
    categoryCounts: counts,
    topCategories: Object.entries(counts).sort((a, b) => b[1] - a[1]).slice(0, 2).map(([c]) => c),
    reportSpamScore: Math.round(Math.max(0, Math.min(100, score))),
    reportsLast24h: last24,
    reportsLast7d: last7,
    lastReportedAt: lastReportedAt ? Timestamp.fromMillis(lastReportedAt) : null,
  };
}

export function combine(d: Doc) {
  let spamScore = Math.max(Number(d.seedSpamScore ?? 0), Number(d.reportSpamScore ?? 0));
  if (d.isVerified && (d.reportsLast7d ?? 0) < 25) spamScore = Math.min(spamScore, 15);
  const categories = [...new Set([...(d.topCategories ?? []), ...(d.seedCategories ?? [])])].slice(0, 3);
  return { spamScore, categories };
}

export const onReportWritten = onDocumentWritten({ document: "reports/{reportId}", region: "asia-south1" }, async (event) => {
  const data = event.data?.after?.data() ?? event.data?.before?.data();
  const e164: string | undefined = data?.phoneNumber;
  if (!e164 || !/^\+[1-9]\d{6,14}$/.test(e164)) return;
  const db = getFirestore();
  const reports = (await db.collection("reports").where("phoneNumber", "==", e164).get()).docs.map((d) => d.data());
  const ref = db.collection("callerNumbers").doc(e164);
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const before = snap.exists ? snap.data()! : {};
    const s = summarize(reports, Date.now());
    const { spamScore, categories } = combine({ ...before, ...s });
    tx.set(ref, {
      normalizedNumber: e164,
      phoneNumber: before.phoneNumber ?? e164,
      isVerified: before.isVerified ?? false,
      ...s,
      spamScore,
      categories,
      source: before.seedSpamScore !== undefined ? "seed+reports" : "reports",
      updatedAt: FieldValue.serverTimestamp(),
      ...(snap.exists ? {} : { createdAt: FieldValue.serverTimestamp() }),
    }, { merge: true });
  });
});
