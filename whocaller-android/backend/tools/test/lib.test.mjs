import { test } from "node:test";
import assert from "node:assert/strict";
import { combine, normalize, parseCsv, reportId, summarize } from "../lib.mjs";

const ts = (ms) => ({ toMillis: () => ms });

test("normalizes Indian numbers to E.164", () => {
  const n = normalize("98765 43210", "IN");
  assert.equal(n.e164, "+919876543210");
  assert.equal(n.countryCode, "+91");
  assert.equal(reportId(n.e164, "uid1"), "919876543210_uid1");
});

test("parses quoted CSV", () => {
  const rows = parseCsv('phoneNumber,displayName\n+12025550142,"Smith, J"\n');
  assert.deepEqual(rows, [{ phoneNumber: "+12025550142", displayName: "Smith, J" }]);
});

test("neutral categories don't raise the spam score", () => {
  const now = Date.now();
  const s = summarize([{ categories: ["DELIVERY"], reportedAt: ts(now) }], now);
  assert.equal(s.reportSpamScore, 0);
  assert.deepEqual(s.topCategories, ["DELIVERY"]);
});

test("more reporters, higher score; seed score is a floor; verified callers stay low", () => {
  const now = Date.now();
  const many = Array.from({ length: 20 }, () => ({ categories: ["SPAM", "ROBOCALL"], reportedAt: ts(now) }));
  const s = summarize(many, now);
  assert.ok(s.reportSpamScore >= 70);
  assert.equal(combine({ seedSpamScore: 80, reportSpamScore: 10 }).spamScore, 80);
  assert.equal(combine({ isVerified: true, reportSpamScore: 90, reportsLast7d: 3 }).spamScore, 15);
});

test("outside list: counts until two users say Not spam; never for verified callers", () => {
  const now = Date.now();
  const listed = { externalSpamScore: 60, externalCategories: ["SPAM"] };
  let c = combine(listed);
  assert.equal(c.spamScore, 60);
  assert.equal(c.externalActive, true);
  assert.deepEqual(c.categories, ["SPAM"]);

  const one = summarize([{ categories: ["NOT_SPAM"], reportedAt: ts(now) }], now);
  assert.equal(one.reportSpamScore, 0);
  assert.equal(combine({ ...listed, ...one }).externalActive, true);

  const two = summarize([{ categories: ["NOT_SPAM"], reportedAt: ts(now) }, { categories: ["NOT_SPAM"], reportedAt: ts(now) }], now);
  c = combine({ ...listed, ...two });
  assert.equal(c.externalActive, false);
  assert.equal(c.spamScore, 0);
  assert.deepEqual(c.categories, []);

  assert.equal(combine({ ...listed, isVerified: true }).externalActive, false);
});
