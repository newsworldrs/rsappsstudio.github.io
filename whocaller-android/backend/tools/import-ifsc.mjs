#!/usr/bin/env node
// Bank branch phone numbers from the RBI IFSC master list (via github.com/razorpay/ifsc,
// dataset in the public domain) → seed CSV.
//
//   node import-ifsc.mjs --out data/ifsc-callers.csv               # downloads the latest release
//   node import-ifsc.mjs --in IFSC.csv --out data/ifsc-callers.csv
//
// Kept: landline, toll-free and other non-mobile numbers that belong to exactly one bank.
// Dropped: mobile numbers (often a staff member's own phone), placeholders like 1234567890,
// and numbers listed for several different banks.
import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { args, parseCsv, strictNumber, titleCase, toSeedCsv } from "./lib.mjs";

const opts = args(process.argv.slice(2));
const GEO = JSON.parse(readFileSync(fileURLToPath(new URL("./ref/in-geo-states.json", import.meta.url)), "utf8"));

/** Old telecom circles vs today's states, plus spelling variants in the RBI file. */
const STATE_GROUPS = [
  ["andhra pradesh", "telangana"], ["bihar", "jharkhand"], ["madhya pradesh", "madhy pradesh", "chhattisgarh"],
  ["punjab", "haryana", "chandigarh", "ludhiana"], ["west bengal", "sikkim"], ["tamil nadu", "puducherry"],
  ["jammu and kashmir", "ladakh"], ["gujarat", "dadra and nagar haveli and daman and diu", "dadar and nagar haveli"],
  ["andaman islands", "nicobar islands", "andaman and nicobar islands"], ["maharashtra", "mumbai"],
  ["delhi", "new delhi"], ["odisha", "odhisa", "orissa"], ["uttar pradesh", "uttarakhand", "uttarkhand"],
  ["karnataka", "dakshin kannad"],
];
const stateKey = (s) => {
  const k = String(s ?? "").toLowerCase().replace(/[^a-z ]/g, " ").replace(/\s+/g, " ").trim();
  const group = STATE_GROUPS.findIndex((g) => g.includes(k));
  return group >= 0 ? `g${group}` : k;
};

/** State of an Indian landline from its area code (longest matching prefix), or null. */
function stateOf(e164) {
  const national = e164.replace(/^\+91/, "");
  for (let i = Math.min(national.length, 8); i > 0; i--) {
    const hit = GEO[national.slice(0, i)];
    if (hit) return hit;
  }
  return null;
}
const out = opts.out ?? "data/ifsc-callers.csv";
const RELEASE = "https://github.com/razorpay/ifsc/releases/latest/download/IFSC.csv";
const BANK_NAMES = "https://raw.githubusercontent.com/razorpay/ifsc/master/src/banknames.json";
const KEEP = new Set(["FIXED_LINE", "TOLL_FREE", "SHARED_COST", "UAN", "FIXED_LINE_OR_MOBILE", "VOIP"]);

async function text(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`${url}: HTTP ${res.status}`);
  return res.text();
}

const csv = opts.in ? readFileSync(opts.in, "utf8") : await text(RELEASE);
let bankNames = {};
try {
  bankNames = JSON.parse(opts.banks ? readFileSync(opts.banks, "utf8") : await text(BANK_NAMES));
} catch (e) {
  console.warn("Bank names not available, using the BANK column only:", e.message);
}

const rows = parseCsv(csv);
const byNumber = new Map();
let dropped = { noContact: 0, invalid: 0, mobile: 0 };
for (const row of rows) {
  const contact = (row.CONTACT ?? "").trim();
  if (!contact || contact === "0") { dropped.noContact++; continue; }
  const n = strictNumber(contact.startsWith("+") ? contact : "+" + contact.replace(/^0+/, ""), "IN") ?? strictNumber(contact, "IN");
  if (!n) { dropped.invalid++; continue; }
  // Indian 10-digit numbers starting 6–9 are mobiles even when the type is ambiguous.
  const mobileLike = /^\+91[6-9]\d{9}$/.test(n.e164);
  if (!KEEP.has(n.type) || mobileLike) { dropped.mobile++; continue; }
  const bank = (row.BANK || bankNames[(row.IFSC ?? "").slice(0, 4)] || "").trim();
  if (!bank) continue;
  if (!byNumber.has(n.e164)) byNumber.set(n.e164, { banks: new Set(), branches: [], type: n.type });
  const entry = byNumber.get(n.e164);
  entry.banks.add(bank);
  entry.branches.push({ branch: row.BRANCH ?? "", city: row.CITY || row.CENTRE || "", state: row.STATE ?? "" });
}

const output = [];
let ambiguous = 0;
let wrongArea = 0;
for (const [e164, { banks, branches, type }] of byNumber) {
  if (banks.size > 1) { ambiguous++; continue; }
  // A landline must be in the same state as its branch, otherwise the RBI entry is a typo.
  if (type !== "TOLL_FREE" && type !== "SHARED_COST" && type !== "UAN") {
    const geo = stateOf(e164);
    if (!geo || !branches.some((b) => stateKey(b.state) === stateKey(geo))) { wrongArea++; continue; }
  }
  const bank = titleCase([...banks][0]);
  let name;
  if (branches.length === 1) {
    const b = branches[0];
    const branch = titleCase(b.branch).replace(/\s*Branch$/i, "");
    const city = titleCase(b.city);
    name = `${bank} – ${branch} Branch${city && !branch.toLowerCase().includes(city.toLowerCase()) ? `, ${city}` : ""}`;
  } else {
    name = bank; // shared by many branches: head office or customer care
  }
  output.push({ phoneNumber: e164, businessName: name.slice(0, 80), categories: "BUSINESS_SERVICE", spamScore: 0, isVerified: true });
}

writeFileSync(out, toSeedCsv(output));
console.log(`${rows.length} branches read → ${output.length} numbers written to ${out}`);
console.log(`Dropped: ${dropped.noContact} without contact, ${dropped.invalid} invalid/placeholder, ${dropped.mobile} mobile, ${ambiguous} shared by several banks, ${wrongArea} area code not in the branch's state.`);
