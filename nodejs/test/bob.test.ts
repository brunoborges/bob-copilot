import assert from "node:assert/strict";
import { chmod, mkdtemp, realpath, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { analyzeWithBob } from "../src/bob.js";

test("passes a read-only analysis request and workspace to Bob", async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "bob-tool-test-"));
  const fakeBob = path.join(directory, "fake-bob");
  await writeFile(
    fakeBob,
    `#!/usr/bin/env node
const args = process.argv.slice(2);
console.log(JSON.stringify({ args, cwd: process.cwd() }));
`,
  );
  await chmod(fakeBob, 0o755);

  const result = JSON.parse(
    await analyzeWithBob(directory, "Find the entry point", {
      bobCommand: fakeBob,
      maxTurns: 3,
      timeoutMs: 5_000,
    }),
  ) as { args: string[]; cwd: string };

  assert.equal(result.cwd, await realpath(directory));
  assert.deepEqual(result.args.slice(0, 2), ["run", "--format"]);
  assert.ok(result.args.includes(directory));
  assert.ok(result.args.includes("3"));
  assert.match(result.args.at(-1) ?? "", /read-only mode/);
  assert.match(result.args.at(-1) ?? "", /Find the entry point/);
  assert.ok(!result.args.includes("--trust"));
});

test("can opt in to trusting the workspace", async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "bob-tool-test-"));
  const fakeBob = path.join(directory, "fake-bob");
  await writeFile(
    fakeBob,
    `#!/usr/bin/env node
console.log(JSON.stringify(process.argv.slice(2)));
`,
  );
  await chmod(fakeBob, 0o755);

  const args = JSON.parse(
    await analyzeWithBob(directory, "Summarize", {
      bobCommand: fakeBob,
      trustWorkspace: true,
    }),
  ) as string[];

  assert.ok(args.includes("--trust"));
});
