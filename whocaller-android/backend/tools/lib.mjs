// Shared by seed.mjs and aggregate.mjs (and mirrored in functions/src/callerNumbers.ts).
// Firestore layout:
//   callerNumbers/{E.164}            caller identity + community spam summary (clients: read only)
//   reports/{E.164 digits}_{uid}     one report per user per number (clients: create/update own)
import { readFileSync } from "node:fs";
import { applicationDefault, cert, initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { parsePhoneNumberFromString } from "libphonenumber-js/max";

/** Report categories, exactly as the app sends them. */
export const CATEGORIES = [
  "SPAM", "TELEMARKETING", "FINANCIAL_SCAM", "FRAUD_FAKE_OFFER", "IMPERSONATION", "BANKING_SCAM",
  "BUSINESS_SERVICE", "DELIVERY", "ROBOCALL", "HARASSMENT", "OTHER", "NOT_SPAM",
];

/** Categories that describe a legitimate caller; they don't raise the spam score. */
export const NEUTRAL = new Set(["BUSINESS_SERVICE", "DELIVERY", "NOT_SPAM"]);

/** This many different users saying "Not spam" clears an outside-list label. */
export const NOT_SPAM_TO_CLEAR = 2;

/** Connects with a service account: FIREBASE_SERVICE_ACCOUNT (JSON text), --key <file>, or GOOGLE_APPLICATION_CREDENTIALS. */
export function connect(keyPath) {
  const inline = process.env.FIREBASE_SERVICE_ACCOUNT;
  let credential;
  let projectId;
  if (keyPath || inline) {
    const json = JSON.parse(keyPath ? readFileSync(keyPath, "utf8") : inline);
    credential = cert(json);
    projectId = json.project_id;
  } else if (process.env.GOOGLE_APPLICATION_CREDENTIALS) {
    credential = applicationDefault();
  } else {
    throw new Error("No credentials. Set FIREBASE_SERVICE_ACCOUNT, GOOGLE_APPLICATION_CREDENTIALS or pass --key <service-account.json>.");
  }
  initializeApp({ credential, projectId });
  return getFirestore();
}

/** E.164 + display parts, or null if the number isn't a possible phone number. */
export function normalize(raw, defaultRegion) {
  const parsed = parsePhoneNumberFromString(String(raw ?? "").trim(), defaultRegion);
  if (!parsed || !parsed.isPossible()) return null;
  return {
    e164: parsed.number,
    display: parsed.formatInternational(),
    countryCode: `+${parsed.countryCallingCode}`,
    region: parsed.country ?? null,
  };
}

/** Report document id for a user and number: "919876543210_<uid>". */
export const reportId = (e164, uid) => `${e164.replace("+", "")}_${uid}`;

const log2 = (x) => Math.log(x) / Math.LN2;

/**
 * Community summary from all reports of one number. Each report is one user (doc id), so counts are
 * unique reporters. Neutral categories (business, delivery) identify the caller but aren't spam.
 */
export function summarize(reports, now = Date.now()) {
  const counts = {};
  let unwanted = 0;
  let last24 = 0;
  let last7 = 0;
  let lastReportedAt = 0;
  for (const r of reports) {
    const cats = (r.categories ?? []).filter((c) => CATEGORIES.includes(c)).slice(0, 2);
    if (cats.length === 0) continue;
    for (const c of cats) counts[c] = (counts[c] ?? 0) + 1;
    const at = r.reportedAt?.toMillis?.() ?? 0;
    lastReportedAt = Math.max(lastReportedAt, at);
    if (cats.some((c) => !NEUTRAL.has(c))) {
      unwanted++;
      if (now - at < 86_400_000) last24++;
      if (now - at < 7 * 86_400_000) last7++;
    }
  }
  let score = unwanted === 0 ? 0
    : Math.min(55, 13 * log2(1 + unwanted)) + Math.min(15, 5 * log2(1 + last24)) + Math.min(10, 3 * log2(1 + last7));
  if (lastReportedAt) {
    const ageDays = (now - lastReportedAt) / 86_400_000;
    score -= ageDays > 365 ? 20 : ageDays > 180 ? 10 : ageDays > 90 ? 5 : 0;
  }
  const top = Object.entries(counts).sort((a, b) => b[1] - a[1]).slice(0, 2).map(([c]) => c);
  return {
    totalReports: reports.length,
    categoryCounts: counts,
    topCategories: top,
    reportSpamScore: Math.round(Math.max(0, Math.min(100, score))),
    reportsLast24h: last24,
    reportsLast7d: last7,
    lastReportedAt: lastReportedAt || null,
  };
}

/** Final fields derived from seed data + report summary. Verified callers stay low unless reports surge. */
export function combine(doc) {
  const seedScore = Number(doc.seedSpamScore ?? 0);
  // An outside spam list counts only until WhoCaller users say "Not spam" (or the caller is verified).
  const notSpam = Number(doc.categoryCounts?.NOT_SPAM ?? 0);
  const externalActive = Number(doc.externalSpamScore ?? 0) > 0 && !doc.isVerified && notSpam < NOT_SPAM_TO_CLEAR;
  let spamScore = Math.max(seedScore, Number(doc.reportSpamScore ?? 0), externalActive ? Number(doc.externalSpamScore) : 0);
  if (doc.isVerified && (doc.reportsLast7d ?? 0) < 25) spamScore = Math.min(spamScore, 15);
  const categories = [...new Set([
    ...(doc.topCategories ?? []).filter((c) => c !== "NOT_SPAM"),
    ...(doc.seedCategories ?? []),
    ...(externalActive ? doc.externalCategories ?? [] : []),
  ])].slice(0, 3);
  return { spamScore, categories, externalActive };
}

/** Minimal CSV reader (quotes, commas and newlines inside quotes). First row = headers. */
export function parseCsv(text) {
  const rows = [];
  let row = [];
  let field = "";
  let quoted = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) {
      if (c === '"' && text[i + 1] === '"') { field += '"'; i++; }
      else if (c === '"') quoted = false;
      else field += c;
    } else if (c === '"') quoted = true;
    else if (c === ",") { row.push(field); field = ""; }
    else if (c === "\n" || c === "\r") {
      if (c === "\r" && text[i + 1] === "\n") i++;
      row.push(field); field = "";
      if (row.some((v) => v.trim() !== "")) rows.push(row);
      row = [];
    } else field += c;
  }
  row.push(field);
  if (row.some((v) => v.trim() !== "")) rows.push(row);
  const [header, ...body] = rows;
  if (!header) return [];
  const keys = header.map((h) => h.trim());
  return body.map((r) => Object.fromEntries(keys.map((k, i) => [k, (r[i] ?? "").trim()])));
}

/** "--name value" / "--flag" command-line options. */
export function args(argv) {
  const out = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith("--")) {
      const next = argv[i + 1];
      if (next !== undefined && !next.startsWith("--")) { out[a.slice(2)] = next; i++; } else out[a.slice(2)] = true;
    } else out._.push(a);
  }
  return out;
}

// ---------- Helpers for the dataset importers ----------

/** Valid number with its libphonenumber type (FIXED_LINE, MOBILE, TOLL_FREE, …), or null. */
export function strictNumber(raw, defaultRegion) {
  const parsed = parsePhoneNumberFromString(String(raw ?? "").trim(), defaultRegion);
  if (!parsed || !parsed.isValid()) return null;
  const national = String(parsed.nationalNumber);
  if (isPlaceholder(national)) return null;
  return { e164: parsed.number, type: parsed.getType() ?? null, region: parsed.country ?? null };
}

/** 1234567890, 9999999999, 0000… — filler values found in public datasets. */
export function isPlaceholder(digits) {
  if (/^(\d)\1+$/.test(digits)) return true;
  if ("01234567890123456789".includes(digits) || "98765432109876543210".includes(digits)) return true;
  return /(\d)\1{6,}/.test(digits);
}

/** "STATE BANK OF INDIA" → "State Bank Of India", keeping short acronyms (SBI, HDFC) as they are. */
export function titleCase(text) {
  return String(text ?? "").trim().replace(/\s+/g, " ").toLowerCase()
    .replace(/\b([a-z])([a-z]*)/g, (_, a, b) => a.toUpperCase() + b)
    .replace(/\b(Sbi|Hdfc|Icici|Idbi|Uco|Idfc|Rbl|Au|Dcb|Csb|Yes|Axis|Pnb|Ltd|Co|Op)\b/g, (w) =>
      ["Ltd", "Co", "Op", "Yes", "Axis"].includes(w) ? w : w.toUpperCase());
}

const csvCell = (v) => {
  const s = v === undefined || v === null ? "" : String(v);
  return /[",\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
};

/** Writes rows in the seed format. */
export function toSeedCsv(rows) {
  const cols = ["phoneNumber", "displayName", "businessName", "categories", "spamScore", "isVerified"];
  return [cols.join(","), ...rows.map((r) => cols.map((c) => csvCell(r[c])).join(","))].join("\n") + "\n";
}
