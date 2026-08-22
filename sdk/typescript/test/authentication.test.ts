/**
 * A key has to change the answer, or the test proves nothing.
 *
 * The other suite runs against a server that identifies nobody: sending a key
 * there is indistinguishable from not sending one. This one starts a server
 * that does identify callers, so the difference is visible.
 */

import { after, before, describe, it } from "node:test";
import * as assert from "node:assert/strict";
import { spawn, ChildProcess } from "node:child_process";
import * as fs from "node:fs";
import * as path from "node:path";
import * as readline from "node:readline";

import { PipeMesh, PipeMeshError } from "../src/client";

const KEY = "pm_the-only-valid-key";
// From dist/test, so four levels: the compiled file is a directory deeper
// than the source it was written in.
const REPO = path.resolve(__dirname, "..", "..", "..", "..");

let runtime: ChildProcess;
let address: string;

function classpath(): string {
  const listing = path.join(REPO, "pipemesh-runtime", "target", "test-classpath.txt");
  return [
    path.join(REPO, "pipemesh-runtime", "target", "classes"),
    path.join(REPO, "pipemesh-runtime", "target", "test-classes"),
    fs.readFileSync(listing, "utf8").trim(),
  ].join(":");
}

before(async () => {
  runtime = spawn("java", ["-cp", classpath(), "io.pipemesh.runtime.TestRuntimeServer"], {
    stdio: ["ignore", "pipe", "ignore"],
    env: { ...process.env, PIPEMESH_TEST_KEY: KEY },
  });

  address = await new Promise<string>((resolve, reject) => {
    readline.createInterface({ input: runtime.stdout! }).once("line", (line) => {
      const port = Number(line.trim());
      if (!Number.isInteger(port)) {
        reject(new Error(`the runtime did not report a port, said: ${line}`));
        return;
      }
      resolve(`localhost:${port}`);
    });
    runtime.once("error", reject);
  });
});

after(() => runtime?.kill());

describe("authentication", () => {
  it("reaches a deployment that authenticates", async () => {
    const mesh = new PipeMesh(address, { apiKey: KEY });
    try {
      const handle = await mesh.execute("policy_check");

      // The organization came from the key, not from the request: the client
      // never named one.
      assert.equal((await mesh.get(handle.executionId)).organization, "acme");
    } finally {
      mesh.close();
    }
  });

  it("is refused a permission without a key", async () => {
    const mesh = new PipeMesh(address, { organization: "acme" });
    try {
      const handle = await mesh.execute("policy_check");
      const updates = mesh.watch(handle.executionId)[Symbol.asyncIterator]();

      await assert.rejects(
        () => updates.next(),
        (failure: PipeMeshError) => failure.code === 7, // PERMISSION_DENIED
      );
    } finally {
      mesh.close();
    }
  });

  it("treats a wrong key as no key", async () => {
    const mesh = new PipeMesh(address, { apiKey: "pm_not-it" });
    try {
      const handle = await mesh.execute("policy_check");
      const updates = mesh.watch(handle.executionId)[Symbol.asyncIterator]();

      await assert.rejects(() => updates.next());
    } finally {
      mesh.close();
    }
  });
});
