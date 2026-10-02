/**
 * WhoCaller REST API v1 — reference implementation on Firebase Cloud Functions + Firestore.
 *
 * Security model
 *  - HTTPS only (Cloud Functions / Hosting).
 *  - Firebase App Check token required on every request (rejects non-genuine clients).
 *  - Firebase Auth ID token optional for reads, required for writes (reports, verification, account).
 *  - Per-user and per-IP rate limits; one report per user per number per 24 h.
 *  - Firestore is closed to clients (see firestore.rules); only this API touches it.
 *  - No contacts, call logs or SMS content are ever received or stored.
 */
import express, { NextFunction, Request, Response } from "express";
import { initializeApp } from "firebase-admin/app";
import { getAppCheck } from "firebase-admin/app-check";
import { getAuth } from "firebase-admin/auth";
import { FieldValue, Timestamp, getFirestore } from "firebase-admin/firestore";
import { onRequest } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { parsePhoneNumberFromString } from "libphonenumber-js/max";
import { scoreNumber } from "./scoring";

initializeApp();
const db = getFirestore();

const REPORT_REASONS = new Set([
  "SPAM", "SCAM", "TELEMARKETING", "FRAUD", "ROBOCALL", "HARASSMENT", "FAKE_BANK_CALL", "FAKE_DELIVERY_CALL", "OTHER",
]);
const HOUR_MS = 60 * 60 * 1000;
const DAY_MS = 24 * HOUR_MS;

interface AuthedRequest extends Request {
  uid?: string;
}

// ---------- middleware ----------

async function requireAppCheck(req: Request, res: Response, next: NextFunction) {
  const token = req.header("X-Firebase-AppCheck");
  if (!token) return res.status(401).json({ error: "app_check_required" });
  try {
    await getAppCheck().verifyToken(token);
    return next();
  } catch {
    return res.status(401).json({ error: "app_check_invalid" });
  }
}

async function optionalAuth(req: AuthedRequest, _res: Response, next: NextFunction) {
  const header = req.header("Authorization");
  if (header?.startsWith("Bearer ")) {
    try {
      const decoded = await getAuth().verifyIdToken(header.substring(7), true);
      req.uid = decoded.uid;
    } catch {
      // Invalid/expired token: treated as anonymous; write endpoints will reject.
    }
  }
  next();
}

function requireAuth(req: AuthedRequest, res: Response, next: NextFunction) {
  if (!req.uid) return res.status(401).json({ error: "sign_in_required" });
  return next();
}

/** Fixed-window rate limiter stored in Firestore (per user, else per IP). */
function rateLimit(name: string, limit: number, windowMs: number) {
  return async (req: AuthedRequest, res: Response, next: NextFunction) => {
    const who = req.uid ?? `ip_${(req.ip ?? "unknown").replace(/[^0-9a-fA-F:.]/g, "")}`;
    const bucket = Math.floor(Date.now() / windowMs);
    const ref = db.collection("rateLimits").doc(`${name}_${who}_${bucket}`);
    try {
      const count = await db.runTransaction(async (tx) => {
        const snap = await tx.get(ref);
        const current = (snap.get("count") as number | undefined) ?? 0;
        tx.set(ref, { count: current + 1, expiresAt: Timestamp.fromMillis((bucket + 2) * windowMs) }, { merge: true });
        return current + 1;
      });
      if (count > limit) return res.status(429).json({ error: "rate_limited" });
      return next();
    } catch {
      return res.status(503).json({ error: "unavailable" });
    }
  };
}

function e164Param(req: Request): string | null {
  const raw = String(req.params.number ?? "");
  const parsed = parsePhoneNumberFromString(raw.startsWith("+") ? raw : `+${raw}`);
  return parsed && parsed.isPossible() ? parsed.number : null;
}

// ---------- app ----------

const app = express();
app.disable("x-powered-by");
app.use(express.json({ limit: "16kb" }));
app.use(requireAppCheck);
app.use(optionalAuth);

const v1 = express.Router();

/** Exchanges a Firebase ID token for an API session (for clients that don't use Firebase directly). */
v1.post("/auth/login", rateLimit("login", 20, 60_000), async (req, res) => {
  const idToken = String(req.body?.idToken ?? "");
  try {
    const decoded = await getAuth().verifyIdToken(idToken, true);
    const expiresAt = decoded.exp * 1000;
    // The Firebase ID token itself is the short-lived bearer token (1 h).
    res.json({ accessToken: idToken, expiresAt, userId: decoded.uid });
  } catch {
    res.status(401).json({ error: "invalid_token" });
  }
});

v1.get("/number/:number", rateLimit("lookup", 120, 60 * 60_000), async (req, res) => {
  const e164 = e164Param(req);
  if (!e164) return res.status(400).json({ error: "invalid_number" });
  const snap = await db.collection("numbers").doc(e164).get();
  if (!snap.exists) return res.status(404).json({ error: "not_found" });
  const d = snap.data()!;
  const now = Date.now();
  const windows = reportWindows(d, now);
  const score = scoreNumber({ ...d, ...windows }, now);
  return res.json({
    number: e164,
    name: d.name ?? null,
    identityType: d.identityType ?? "UNKNOWN",
    category: score.category,
    spamScore: score.score,
    confidence: score.confidence,
    reportCount: d.reportCount ?? 0,
    reportsLast24h: windows.reportsLast24h,
    reportsLast7d: windows.reportsLast7d,
    blockCount: d.blockCount ?? 0,
    lastReportedAt: d.lastReportedAt?.toMillis?.() ?? null,
    categoryVotes: d.reportsByReason ?? {},
    verified: d.verified === true && !!d.verificationDate,
    businessId: d.businessId ?? null,
    carrier: d.carrier ?? null,
    lineType: d.lineType ?? null,
    region: d.region ?? parsePhoneNumberFromString(e164)?.country ?? null,
    updatedAt: now,
  });
});

v1.post("/number/:number/report", requireAuth, rateLimit("report", 30, DAY_MS), async (req: AuthedRequest, res) => {
  const e164 = e164Param(req);
  if (!e164) return res.status(400).json({ error: "invalid_number" });
  const reason = String(req.body?.reason ?? "");
  if (!REPORT_REASONS.has(reason)) return res.status(422).json({ error: "invalid_reason" });
  const comment = typeof req.body?.comment === "string" ? req.body.comment.replace(/[\u0000-\u0009\u000B-\u001F]/g, "").slice(0, 500) : null;
  const clientReportId = String(req.body?.clientReportId ?? "").slice(0, 64);
  if (!clientReportId) return res.status(422).json({ error: "missing_client_id" });

  const numberRef = db.collection("numbers").doc(e164);
  // One report per user per number: doc id is the uid, so retries and re-reports can't inflate counts.
  const reportRef = numberRef.collection("reports").doc(req.uid!);
  try {
    const outcome = await db.runTransaction(async (tx) => {
      const existing = await tx.get(reportRef);
      const now = Date.now();
      const update: Record<string, unknown> = {
        region: parsePhoneNumberFromString(e164)?.country ?? null,
        [`reportsByReason.${reason}`]: FieldValue.increment(1),
        [`buckets.h${Math.floor(now / HOUR_MS)}`]: FieldValue.increment(1),
        [`buckets.d${Math.floor(now / DAY_MS)}`]: FieldValue.increment(1),
        lastReportedAt: Timestamp.fromMillis(now),
        updatedAt: Timestamp.fromMillis(now),
      };
      if (existing.exists) {
        const prev = existing.data()!;
        if (prev.clientReportId === clientReportId) return "duplicate";
        if (now - (prev.createdAt as Timestamp).toMillis() < DAY_MS) return "duplicate";
        // A re-report replaces the user's previous vote instead of adding a second one.
        if (prev.reason !== reason) update[`reportsByReason.${prev.reason}`] = FieldValue.increment(-1);
        else delete update[`reportsByReason.${reason}`];
      } else {
        update.reportCount = FieldValue.increment(1);
      }
      tx.set(reportRef, {
        uid: req.uid, reason, comment, clientReportId, createdAt: Timestamp.fromMillis(now),
        // Comments are moderated before any use; they are never shown publicly verbatim.
        moderation: comment ? "pending" : "none",
      });
      tx.set(numberRef, update, { merge: true });
      return "accepted";
    });
    if (outcome === "duplicate") return res.status(409).json({ error: "duplicate" });
    return res.json({ reportId: `${e164}:${req.uid}`, accepted: true });
  } catch {
    return res.status(503).json({ error: "unavailable" });
  }
});

v1.get("/number/:number/reports", rateLimit("lookup", 120, 60 * 60_000), async (req, res) => {
  const e164 = e164Param(req);
  if (!e164) return res.status(400).json({ error: "invalid_number" });
  const snap = await db.collection("numbers").doc(e164).get();
  const d = snap.data() ?? {};
  // Aggregates only: individual reporters and comments are never exposed.
  res.json({
    number: e164,
    total: d.reportCount ?? 0,
    byReason: d.reportsByReason ?? {},
    lastReportedAt: d.lastReportedAt?.toMillis?.() ?? null,
  });
});

v1.post("/number/:number/block", requireAuth, rateLimit("block", 100, DAY_MS), async (req: AuthedRequest, res) => {
  const e164 = e164Param(req);
  if (!e164) return res.status(400).json({ error: "invalid_number" });
  const markerRef = db.collection("numbers").doc(e164).collection("blocks").doc(req.uid!);
  await db.runTransaction(async (tx) => {
    const marker = await tx.get(markerRef);
    if (marker.exists) return;
    tx.set(markerRef, { at: Timestamp.now() });
    tx.set(db.collection("numbers").doc(e164), { blockCount: FieldValue.increment(1) }, { merge: true });
  });
  res.status(204).end();
});

v1.get("/search", rateLimit("search", 60, 60 * 60_000), async (req, res) => {
  const q = String(req.query.q ?? "").trim().toLowerCase().slice(0, 60);
  const region = typeof req.query.region === "string" ? req.query.region.toUpperCase().slice(0, 2) : null;
  if (q.length < 2) return res.json({ results: [] });
  let query = db.collection("businesses").where("nameLower", ">=", q).where("nameLower", "<=", q + "").limit(25);
  if (region) query = query.where("country", "==", region);
  const snap = await query.get();
  return res.json({ results: snap.docs.map((d) => businessDto(d.id, d.data())) });
});

v1.get("/business/:id", rateLimit("lookup", 120, 60 * 60_000), async (req, res) => {
  const snap = await db.collection("businesses").doc(String(req.params.id).slice(0, 128)).get();
  if (!snap.exists) return res.status(404).json({ error: "not_found" });
  return res.json(businessDto(snap.id, snap.data()!));
});

/** Starts a verification request. Verification itself is done by staff after ownership checks. */
v1.post("/business/verify", requireAuth, rateLimit("verify", 3, DAY_MS), async (req: AuthedRequest, res) => {
  const b = req.body ?? {};
  const phone = parsePhoneNumberFromString(String(b.phoneNumber ?? ""));
  const email = String(b.email ?? "").trim();
  if (!phone?.isValid() || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || !String(b.businessName ?? "").trim()) {
    return res.status(422).json({ error: "invalid_request" });
  }
  const doc = await db.collection("verificationRequests").add({
    uid: req.uid,
    businessName: String(b.businessName).slice(0, 120),
    phoneNumber: phone.number,
    category: String(b.category ?? "").slice(0, 60),
    website: b.website ? String(b.website).slice(0, 200) : null,
    email,
    country: String(b.country ?? phone.country ?? "").slice(0, 2),
    status: "PENDING",
    createdAt: Timestamp.now(),
  });
  return res.json({ requestId: doc.id, status: "PENDING" });
});

v1.get("/config", (_req, res) => {
  res.set("Cache-Control", "public, max-age=3600");
  res.json({ minSupportedVersion: 1, lookupCacheTtlHours: 72, spamListMaxEntries: 20000, freeSearchesPerDay: 50 });
});

v1.get("/countries", (_req, res) => {
  res.set("Cache-Control", "public, max-age=86400");
  res.json(["IN", "US", "CA", "GB", "AU", "DE", "FR", "AE", "SG"].map((region) => ({
    region,
    callingCode: Number(parsePhoneNumberFromString(exampleFor(region))?.countryCallingCode ?? 0),
    name: region,
    supported: true,
  })));
});

v1.get("/categories", (_req, res) => {
  res.json([
    "SAFE", "UNKNOWN", "TELEMARKETING", "SPAM", "SCAM", "FRAUD", "ROBOCALL", "DEBT_COLLECTION", "POLITICAL", "CHARITY", "BUSINESS",
  ].map((id) => ({ id, severity: ["SCAM", "FRAUD"].includes(id) ? 3 : ["SPAM", "ROBOCALL"].includes(id) ? 2 : 1 })));
});

/** Frequently reported numbers for the offline spam list. */
v1.get("/spam/top", rateLimit("spamlist", 10, DAY_MS), async (req, res) => {
  const region = String(req.query.region ?? "").toUpperCase().slice(0, 2);
  if (region.length !== 2) return res.status(400).json({ error: "invalid_region" });
  const snap = await db.collection("numbers").where("region", "==", region).orderBy("spamScore", "desc").limit(5000).get();
  const now = Date.now();
  const entries = snap.docs
    .map((d) => ({ id: d.id, data: d.data(), score: scoreNumber({ ...d.data(), ...reportWindows(d.data(), now) }, now) }))
    .filter((e) => e.score.score >= 50)
    .map((e) => ({
      number: e.id,
      category: e.score.category,
      spamScore: e.score.score,
      confidence: e.score.confidence,
      reportCount: e.data.reportCount ?? 0,
      categoryVotes: e.data.reportsByReason ?? {},
      region,
    }));
  return res.json({ region, generatedAt: now, entries });
});

/** Verifies a Play subscription with the Play Developer API (service account held server-side). */
v1.post("/billing/verify", requireAuth, rateLimit("billing", 20, DAY_MS), async (_req, res) => {
  // Implement with googleapis androidpublisher.purchases.subscriptionsv2.get using the
  // service account configured in Secret Manager. Never ship Play credentials in the app.
  res.status(501).json({ error: "not_implemented" });
});

v1.delete("/account", requireAuth, async (req: AuthedRequest, res) => {
  const uid = req.uid!;
  // Anonymise this user's reports (keep aggregate counts, drop comments and links to the user).
  const reports = await db.collectionGroup("reports").where("uid", "==", uid).get();
  const batch = db.batch();
  reports.docs.forEach((d) => batch.update(d.ref, { uid: null, comment: null, moderation: "deleted_user" }));
  batch.delete(db.collection("users").doc(uid));
  await batch.commit();
  await getAuth().deleteUser(uid);
  res.status(204).end();
});

app.use("/api/v1", v1);
app.use((_req, res) => res.status(404).json({ error: "not_found" }));

/** Reports in the last 24 h / 7 days from hourly and daily buckets. */
function reportWindows(d: FirebaseFirestore.DocumentData, now: number) {
  const buckets: Record<string, number> = d.buckets ?? {};
  const hourNow = Math.floor(now / HOUR_MS);
  const dayNow = Math.floor(now / DAY_MS);
  let last24 = 0;
  let last7 = 0;
  for (const [key, value] of Object.entries(buckets)) {
    const index = Number(key.substring(1));
    if (key.startsWith("h") && hourNow - index < 24) last24 += value;
    if (key.startsWith("d") && dayNow - index < 7) last7 += value;
  }
  return { reportsLast24h: last24, reportsLast7d: last7 };
}

/** Daily: drop old buckets and refresh the stored score used to build regional spam lists. */
export const maintainNumbers = onSchedule({ schedule: "every 24 hours", region: "asia-south1" }, async () => {
  const now = Date.now();
  const since = Timestamp.fromMillis(now - 8 * DAY_MS);
  const snap = await db.collection("numbers").where("updatedAt", ">=", since).get();
  let batch = db.batch();
  let n = 0;
  for (const doc of snap.docs) {
    const d = doc.data();
    const buckets: Record<string, number> = d.buckets ?? {};
    const kept = Object.fromEntries(Object.entries(buckets).filter(([k]) => {
      const i = Number(k.substring(1));
      return k.startsWith("h") ? Math.floor(now / HOUR_MS) - i < 24 : Math.floor(now / DAY_MS) - i < 8;
    }));
    const score = scoreNumber({ ...d, ...reportWindows(d, now) }, now);
    batch.update(doc.ref, { buckets: kept, spamScore: score.score, category: score.category });
    if (++n % 400 === 0) {
      await batch.commit();
      batch = db.batch();
    }
  }
  await batch.commit();
  // Expired rate-limit windows.
  const old = await db.collection("rateLimits").where("expiresAt", "<", Timestamp.fromMillis(now)).limit(5000).get();
  const cleanup = db.batch();
  old.docs.forEach((d) => cleanup.delete(d.ref));
  await cleanup.commit();
});

function businessDto(id: string, d: FirebaseFirestore.DocumentData) {
  return {
    businessId: id,
    name: d.name,
    phoneNumbers: d.phoneNumbers ?? [],
    category: d.category ?? null,
    address: d.address ?? null,
    website: d.website ?? null,
    logo: d.logo ?? null,
    email: d.email ?? null,
    // "Verified" only with a completed verification record.
    verified: d.verified === true && !!d.verificationDate,
    verificationDate: d.verificationDate?.toMillis?.() ?? null,
    country: d.country ?? null,
    language: d.language ?? null,
    hours: d.hours ?? null,
    rating: d.ratingCount > 0 ? d.rating : null,
    ratingCount: d.ratingCount ?? null,
  };
}

function exampleFor(region: string): string {
  const examples: Record<string, string> = {
    IN: "+919876543210", US: "+16502530000", CA: "+14165550100", GB: "+442079460321", AU: "+61291234567",
    DE: "+4930123456", FR: "+33123456789", AE: "+97142345678", SG: "+6561234567",
  };
  return examples[region] ?? "+10000000000";
}

export const api = onRequest({ region: "asia-south1", cors: false, maxInstances: 50 }, app);

// Direct-Firestore mode: keeps callerNumbers in sync with reports written by the app.
export { onReportWritten } from "./callerNumbers";
// WHOCALLER VIDEO (experimental): remove this line to remove app-to-app video calls.
export { onVideoCallCreated } from "./videoCalls";
