#!/usr/bin/env node
// Organisations based in India with an official phone number on Wikidata (CC0) → seed CSV.
// People are excluded.
//
//   node import-wikidata.mjs --out data/wikidata-callers.csv
import { writeFileSync } from "node:fs";
import { args, strictNumber, toSeedCsv } from "./lib.mjs";

const opts = args(process.argv.slice(2));
const out = opts.out ?? "data/wikidata-callers.csv";
const country = String(opts.country ?? "Q668"); // Q668 = India

const query = `
SELECT ?item ?itemLabel ?phone WHERE {
  ?item wdt:P1329 ?phone .
  { ?item wdt:P17 wd:${country} } UNION { ?item wdt:P159 ?hq . ?hq wdt:P17 wd:${country} }
  FILTER NOT EXISTS { ?item wdt:P31 wd:Q5 }
  SERVICE wikibase:label { bd:serviceParam wikibase:language "en". }
}`;
const res = await fetch("https://query.wikidata.org/sparql?format=json&query=" + encodeURIComponent(query), {
  headers: { "User-Agent": "WhoCallerDatasetImporter/1.0 (https://github.com/newsworldrs/rsappsstudio.github.io)", Accept: "application/sparql-results+json" },
});
if (!res.ok) throw new Error(`Wikidata: HTTP ${res.status}`);
const bindings = (await res.json()).results.bindings;

const byNumber = new Map();
for (const b of bindings) {
  const name = b.itemLabel?.value ?? "";
  if (!name || /^Q\d+$/.test(name)) continue; // no English label
  const n = strictNumber(b.phone?.value, "IN");
  if (!n) continue;
  if (!byNumber.has(n.e164)) byNumber.set(n.e164, new Set());
  byNumber.get(n.e164).add(name);
}
const rows = [];
for (const [e164, names] of byNumber) {
  if (names.size > 2) continue;
  rows.push({ phoneNumber: e164, businessName: [...names][0].slice(0, 80), categories: "BUSINESS_SERVICE", spamScore: 0 });
}
writeFileSync(out, toSeedCsv(rows));
console.log(`${bindings.length} Wikidata entries → ${rows.length} numbers written to ${out}`);
