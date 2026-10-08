#!/usr/bin/env node
// Publishes the SMS spam model so installed apps download it (Firestore appConfig/smsSpamModel).
//   node publish-sms-model.mjs ../../core/domain/src/main/resources/whocaller/sms_spam_model.txt --version 2
// Bump --version every time; phones only download a version newer than the one they have.
import { readFileSync } from "node:fs";
import { FieldValue } from "firebase-admin/firestore";
import { args, connect } from "./lib.mjs";

const opts = args(process.argv.slice(2));
const file = opts._[0];
const version = Number(opts.version);
if (!file || !Number.isInteger(version) || version < 1) {
  console.error("Usage: node publish-sms-model.mjs <sms_spam_model.txt> --version <n> [--key service-account.json]");
  process.exit(1);
}
const model = readFileSync(file, "utf8");
if (!/^bias -?\d/m.test(model)) throw new Error("Not a model file (no bias line).");
const db = connect(opts.key);
await db.collection("appConfig").doc("smsSpamModel").set({ version, model, updatedAt: FieldValue.serverTimestamp() });
console.log(`Published SMS model version ${version} (${model.length} bytes).`);
