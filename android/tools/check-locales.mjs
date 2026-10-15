#!/usr/bin/env node
/*
 * Locale parity for the port's own UI strings.
 *
 * Every `res/values-<lang>/strings.xml` must carry exactly the keys of `res/values/strings.xml`, in the same
 * order, with the same positional placeholders (`%1$s` …) — a missing key silently falls back to
 * English, and a mismatched arity is a runtime `IllegalFormatException`, not a build error. This is the
 * check §25.2/§28 ran by hand; it runs it every time now.
 *
 *   node android/tools/check-locales.mjs
 */
import { readFileSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const resDir = join(dirname(fileURLToPath(import.meta.url)), "..", "app", "src", "main", "res");

/** name -> raw value for one strings.xml. */
function parse(path) {
  const text = readFileSync(path, "utf8");
  const map = new Map();
  for (const m of text.matchAll(/<string name="([^"]+)">([\s\S]*?)<\/string>/g)) {
    map.set(m[1], m[2]);
  }
  return map;
}

/** The set of positional placeholders a string uses, as sorted indices. */
function placeholders(value) {
  return [...new Set([...value.matchAll(/%(\d+)\$[A-Za-z]/g)].map((m) => Number(m[1])))]
    .sort((a, b) => a - b)
    .join(",");
}

function strayPercent(value) {
  for (const m of value.matchAll(/%/g)) {
    const rest = value.slice(m.index + 1);
    if (!/^\d+\$/.test(rest) && rest[0] !== "%") return true;
  }
  return false;
}

const locales = readdirSync(resDir)
  .filter((d) => d === "values" || d.startsWith("values-"))
  .sort((a, b) => (a === "values" ? -1 : b === "values" ? 1 : a.localeCompare(b)));

const base = parse(join(resDir, "values", "strings.xml"));
const baseKeys = [...base.keys()];
const problems = [];

for (const locale of locales) {
  const file = join(resDir, locale, "strings.xml");
  const strings = parse(file);
  const keys = [...strings.keys()];

  for (const key of baseKeys) {
    if (!strings.has(key)) problems.push(`${locale}: missing ${key}`);
  }
  for (const key of keys) {
    if (!base.has(key)) problems.push(`${locale}: extra ${key}`);
  }
  if (keys.join("\n") !== baseKeys.join("\n")) {
    problems.push(`${locale}: key order differs from values/strings.xml`);
  }
  for (const [key, value] of strings) {
    if (!base.has(key)) continue;
    const want = placeholders(base.get(key));
    const got = placeholders(value);
    if (want !== got) {
      problems.push(`${locale}: ${key} placeholders %[${got}] != %[${want}]`);
    }
    if (strayPercent(value)) problems.push(`${locale}: ${key} has a stray '%'`);
  }
  console.log(`${locale}: ${keys.length} keys`);
}

if (problems.length > 0) {
  console.log(`\n${problems.length} problem(s):`);
  for (const p of problems) console.log(`  ${p}`);
  process.exit(1);
}
console.log(`\nall ${locales.length} locales agree on ${baseKeys.length} keys`);
