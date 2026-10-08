#!/usr/bin/env node
// Businesses with phone numbers from OpenStreetMap → seed CSV.
// Data © OpenStreetMap contributors, ODbL 1.0 — the app must show this credit (it does, in Privacy).
//
// Input: GeoJSON sequence made with osmium (the GitHub workflow does this for you):
//   osmium tags-filter india-latest.osm.pbf nwr/phone nwr/contact:phone nwr/contact:mobile -o phones.osm.pbf
//   osmium export phones.osm.pbf -f geojsonseq -o phones.geojsonseq
//   node import-osm.mjs phones.geojsonseq --out data/osm-callers.csv
import { createReadStream, writeFileSync } from "node:fs";
import { createInterface } from "node:readline";
import { args, strictNumber, toSeedCsv } from "./lib.mjs";

const opts = args(process.argv.slice(2));
const input = opts._[0];
if (!input) {
  console.error("Usage: node import-osm.mjs <phones.geojsonseq> [--out data/osm-callers.csv] [--region IN]");
  process.exit(1);
}
const out = opts.out ?? "data/osm-callers.csv";
const region = String(opts.region ?? "IN").toUpperCase();

// Kinds of places that are businesses or public services (not homes or people).
const KINDS = ["shop", "amenity", "office", "craft", "tourism", "healthcare", "leisure", "club", "emergency"];
// Places that should never be labelled from map data.
const SKIP = new Set(["amenity=bench", "amenity=toilets", "amenity=parking", "amenity=waste_basket"]);

const byNumber = new Map();
let features = 0;
let kept = 0;
const rl = createInterface({ input: createReadStream(input, "utf8"), crlfDelay: Infinity });
for await (const rawLine of rl) {
  const line = rawLine.replace(/^\x1e/, "").trim(); // geojsonseq record separator
  if (!line) continue;
  let f;
  try { f = JSON.parse(line); } catch { continue; }
  features++;
  const t = f.properties ?? {};
  const kind = KINDS.find((k) => t[k]);
  if (!kind || SKIP.has(`${kind}=${t[kind]}`)) continue;
  const name = String(t["name:en"] ?? t.name ?? t.brand ?? "").trim();
  if (name.length < 2) continue;
  const city = String(t["addr:city"] ?? t["addr:district"] ?? "").trim();
  const label = (city && !name.toLowerCase().includes(city.toLowerCase()) ? `${name}, ${city}` : name).slice(0, 80);
  const phones = [t.phone, t["contact:phone"], t["contact:mobile"]].filter(Boolean).join(";").split(/[;,/]|\s{2,}/);
  for (const p of phones) {
    const n = strictNumber(p, region);
    if (!n) continue;
    if (!byNumber.has(n.e164)) byNumber.set(n.e164, new Map());
    const names = byNumber.get(n.e164);
    names.set(label, (names.get(label) ?? 0) + 1);
    kept++;
  }
}

const rows = [];
let shared = 0;
for (const [e164, names] of byNumber) {
  // Same number on many different places (e.g. a chain's call centre with different names): skip.
  if (names.size > 3) { shared++; continue; }
  const [label] = [...names.entries()].sort((a, b) => b[1] - a[1])[0];
  rows.push({ phoneNumber: e164, businessName: label, categories: "BUSINESS_SERVICE", spamScore: 0 });
}
writeFileSync(out, toSeedCsv(rows));
console.log(`${features} map features, ${kept} phone numbers → ${rows.length} unique numbers written to ${out} (${shared} shared by many places skipped).`);
