#!/usr/bin/env node
// USA: builds a spam list from the FCC's public consumer-complaint data (unwanted calls / robocalls),
// US government public data. Numbers are counted per caller ID; only numbers with several recent
// complaints are kept, because complaint caller IDs are sometimes spoofed.
//
//   node import-us-fcc.mjs --out data/us-fcc-callers.csv [--since-days 365] [--min 3]
//
// Output feeds external-list.mjs (an outside list: softer label, never auto-blocked, cleared by
// "Not spam" votes, removable in one command).
import { writeFileSync } from "node:fs";
import { args, strictNumber } from "./lib.mjs";

const opts = args(process.argv.slice(2));
const out = String(opts.out ?? "data/us-fcc-callers.csv");
const sinceDays = Number(opts["since-days"] ?? 365);
const min = Number(opts.min ?? 3);
const DATASET = String(opts.dataset ?? "3xyp-aqkj"); // CGB - Consumer Complaints Data
const BASE = "https://opendata.fcc.gov";

async function get(url) {
  for (let attempt = 1; attempt <= 4; attempt++) {
    const res = await fetch(url, { headers: { "User-Agent": "WhoCallerDatasetImporter/1.0", Accept: "application/json" } });
    if (res.ok) return res.json();
    console.log(`HTTP ${res.status} for ${url} (attempt ${attempt})`);
    await new Promise((r) => setTimeout(r, attempt * 3000));
  }
  throw new Error("FCC API unavailable: " + url);
}

// Column names differ between dataset versions, so find them from the metadata.
const meta = await get(`${BASE}/api/views/${DATASET}.json`);
const cols = (meta.columns ?? []).map((c) => c.fieldName).filter((f) => !f.startsWith(":"));
console.log("Dataset:", meta.name, "| columns:", cols.join(", "));
const callerCol = cols.find((c) => /caller_id/.test(c)) ?? cols.find((c) => /phone.*number|number.*phone/.test(c));
const dateCol = cols.find((c) => /^issue_date$/.test(c)) ?? cols.find((c) => /ticket_created|date_created|created/.test(c)) ?? cols.find((c) => /date/.test(c));
const issueCol = cols.find((c) => /^issue$/.test(c));
const typeCol = cols.find((c) => /type_of_call|call_type|method/.test(c));
if (!callerCol || !dateCol) throw new Error(`Couldn't find caller-id / date columns in ${cols.join(", ")}`);
console.log(`Using caller=${callerCol} date=${dateCol} issue=${issueCol ?? "-"} type=${typeCol ?? "-"}`);

const since = new Date(Date.now() - sinceDays * 86_400_000).toISOString().slice(0, 10);
const where = [`${dateCol} > '${since}'`, `${callerCol} IS NOT NULL`];
if (issueCol) where.push(`(upper(${issueCol}) like '%UNWANTED%' OR upper(${issueCol}) like '%ROBOCALL%' OR upper(${issueCol}) like '%TELEMARKET%')`);

const counts = new Map(); // E.164 → { n, last, robo }
const PAGE = 50_000;
for (let offset = 0; ; offset += PAGE) {
  const q = new URLSearchParams({
    $select: `${callerCol} as num, count(*) as n, max(${dateCol}) as last` + (typeCol ? `, max(${typeCol}) as kind` : ""),
    $where: where.join(" AND "),
    $group: callerCol,
    $having: `count(*) >= 1`,
    $order: "n DESC",
    $limit: String(PAGE),
    $offset: String(offset),
  });
  const rows = await get(`${BASE}/resource/${DATASET}.json?${q}`);
  console.log(`page ${offset / PAGE + 1}: ${rows.length} caller IDs`);
  for (const r of rows) {
    // Several raw spellings can be the same number: merge after normalising.
    const n = strictNumber(r.num, "US");
    if (!n || !n.e164.startsWith("+1")) continue;
    const prev = counts.get(n.e164) ?? { n: 0, last: "", robo: false };
    prev.n += Number(r.n ?? 0);
    if (String(r.last ?? "") > prev.last) prev.last = String(r.last ?? "");
    if (/robo|prerecord|artificial/i.test(String(r.kind ?? ""))) prev.robo = true;
    counts.set(n.e164, prev);
  }
  if (rows.length < PAGE) break;
}

/** More independent complaints → higher (still capped; outside lists never count as certain). */
const scoreFor = (n) => (n >= 25 ? 78 : n >= 10 ? 72 : n >= 5 ? 66 : 60);
const kept = [...counts.entries()].filter(([, v]) => v.n >= min).sort((a, b) => b[1].n - a[1].n);
const lines = ["phoneNumber,category,spamScore,reports,lastSeen"];
for (const [e164, v] of kept) lines.push([e164, v.robo ? "ROBOCALL" : "SPAM", scoreFor(v.n), v.n, v.last.slice(0, 10)].join(","));
writeFileSync(out, lines.join("\n") + "\n");
console.log(`${counts.size} US caller IDs in the last ${sinceDays} days; ${kept.length} with at least ${min} complaints → ${out}`);
