/**
 * Server-side spam score. Mirrors the on-device RuleBasedSpamScoreEngine so both agree, and is the
 * place to plug in an ML model later. Only real signals are used; no data means score 0 / UNKNOWN.
 */
export interface Score {
  score: number;
  category: string;
  confidence: number;
}

const SEVERE = new Set(["SCAM", "FRAUD"]);
const REASON_TO_CATEGORY: Record<string, string> = {
  SPAM: "SPAM", SCAM: "SCAM", TELEMARKETING: "TELEMARKETING", FRAUD: "FRAUD", ROBOCALL: "ROBOCALL",
  HARASSMENT: "SPAM", FAKE_BANK_CALL: "FRAUD", FAKE_DELIVERY_CALL: "SCAM", OTHER: "SPAM",
};

const log2 = (x: number) => Math.log(x) / Math.LN2;

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function scoreNumber(d: Record<string, any>, now: number): Score {
  const reports = Math.max(0, d.reportCount ?? 0);
  const last24 = Math.max(0, d.reportsLast24h ?? 0);
  const last7 = Math.max(0, d.reportsLast7d ?? 0);
  const blocks = Math.max(0, d.blockCount ?? 0);
  const evidence = reports + blocks + (d.verified ? 1 : 0);
  if (evidence === 0) return { score: 0, category: d.identityType === "BUSINESS" ? "BUSINESS" : "UNKNOWN", confidence: 0 };

  let score = Math.min(55, 13 * log2(1 + reports))
    + Math.min(15, 5 * log2(1 + last24))
    + Math.min(10, 3 * log2(1 + last7))
    + Math.min(10, 3 * log2(1 + blocks));

  const lastReported = d.lastReportedAt?.toMillis?.() as number | undefined;
  if (lastReported) {
    const ageDays = (now - lastReported) / 86_400_000;
    score -= ageDays > 365 ? 20 : ageDays > 180 ? 10 : ageDays > 90 ? 5 : 0;
  }
  if (d.verified && d.verificationDate) score = Math.min(score, last7 >= 25 ? 60 : 15);
  score = Math.round(Math.max(0, Math.min(100, score)));

  const votes: Record<string, number> = {};
  for (const [reason, count] of Object.entries(d.reportsByReason ?? {})) {
    const cat = REASON_TO_CATEGORY[reason] ?? "SPAM";
    votes[cat] = (votes[cat] ?? 0) + Number(count);
  }
  let category = Object.entries(votes).sort((a, b) => b[1] - a[1])[0]?.[0] ?? (score >= 50 ? "SPAM" : "UNKNOWN");
  if (score < 20 && category !== "BUSINESS") category = d.verified ? "BUSINESS" : "UNKNOWN";
  if (SEVERE.has(category) && !(score >= 50 && (votes.SCAM ?? 0) + (votes.FRAUD ?? 0) >= 3)) category = "SPAM";

  const confidence = Math.max(Math.min(1, log2(1 + reports + blocks) / log2(51)), d.verified ? 0.9 : 0);
  return { score, category, confidence: Math.round(confidence * 100) / 100 };
}
