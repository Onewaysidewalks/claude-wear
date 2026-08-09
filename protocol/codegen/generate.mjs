#!/usr/bin/env node
/**
 * Regenerates the TypeScript and Kotlin views of the wire protocol.
 *
 *   node protocol/codegen/generate.mjs [--check]
 *
 * --check exits non-zero if either output is stale, which is what CI's contract job
 * asserts (it also checks the working tree is clean afterwards).
 */
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, relative, resolve } from "node:path";
import { loadSchemas } from "./schema.mjs";
import { emitTypeScript } from "./ts.mjs";
import { emitKotlin, KOTLIN_PACKAGE } from "./kotlin.mjs";

const repoRoot = resolve(new URL("../..", import.meta.url).pathname);

const outputs = [
  {
    path: resolve(repoRoot, "bridge/src/protocol.ts"),
    render: emitTypeScript,
  },
  {
    path: resolve(repoRoot, "wear/protocol/src/main/kotlin", KOTLIN_PACKAGE.replace(/\./g, "/"), "Protocol.kt"),
    render: emitKotlin,
  },
];

const check = process.argv.includes("--check");
const ir = loadSchemas();
let stale = 0;

for (const { path, render } of outputs) {
  const next = render(ir);
  let current = null;
  try {
    current = readFileSync(path, "utf8");
  } catch {
    /* not generated yet */
  }
  const rel = relative(repoRoot, path);
  if (current === next) {
    if (!check) console.log(`  unchanged  ${rel}`);
    continue;
  }
  stale += 1;
  if (check) {
    console.error(`  STALE      ${rel}`);
  } else {
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, next);
    console.log(`  wrote      ${rel}`);
  }
}

if (check && stale > 0) {
  console.error(`\n${stale} generated file(s) are out of date. Run \`make protocol\` and commit the result.`);
  process.exit(1);
}
