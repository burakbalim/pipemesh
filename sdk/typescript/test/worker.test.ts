/**
 * Business code in this process, called by the runtime as a capability.
 *
 * The workflow says only `"capability": "calculate_discount"`. That it happens
 * to be a TypeScript function in a test process is something it never learns.
 */

import { after, before, describe, it } from "node:test";
import * as assert from "node:assert/strict";
import { spawn, ChildProcess } from "node:child_process";
import * as fs from "node:fs";
import * as path from "node:path";
import * as readline from "node:readline";

import { PipeMesh } from "../src/client";
import { CapabilityFailure, CapabilityFunction, PipeMeshWorker } from "../src/worker";

const REPO = path.resolve(__dirname, "..", "..", "..", "..");
const CLASSPATH_FILE = path.join(REPO, "pipemesh-grpc", "target", "test-classpath.txt");
const CLASSES = [
  path.join(REPO, "pipemesh-grpc", "target", "classes"),
  path.join(REPO, "pipemesh-grpc", "target", "test-classes"),
];

let runtime: ChildProcess;
let address: string;
let mesh: PipeMesh;

function startRuntime(): Promise<string> {
  const classpath = [...CLASSES, fs.readFileSync(CLASSPATH_FILE, "utf8").trim()].join(":");
  runtime = spawn("java", ["-cp", classpath, "io.pipemesh.grpc.TestRuntimeServer"], {
    stdio: ["ignore", "pipe", "ignore"],
  });

  return new Promise((resolve, reject) => {
    readline.createInterface({ input: runtime.stdout! }).once("line", (line) => {
      const port = Number(line.trim());
      Number.isInteger(port) ? resolve(`localhost:${port}`) : reject(new Error(line));
    });
    runtime.once("error", reject);
  });
}

async function withWorker<T>(
  run: CapabilityFunction,
  body: () => Promise<T>,
  name = "calculate_discount",
): Promise<T> {
  const worker = new PipeMeshWorker(address, { organization: "acme" });
  worker.capability(name, run).start();
  await new Promise((wake) => setTimeout(wake, 300));

  try {
    return await body();
  } finally {
    worker.stop();
  }
}

describe("PipeMesh worker", () => {
  before(async () => {
    address = await startRuntime();
    mesh = new PipeMesh(address, { organization: "acme" });
  });

  after(() => {
    mesh?.close();
    runtime?.kill();
  });

  it("runs a capability that lives in this process", async () => {
    await withWorker(
      (customer) => ({ rate: customer.tier === "gold" ? 0.2 : 0.05 }),
      async () => {
        const handle = await mesh.execute("discount_check", { tier: "gold" });

        assert.equal(handle.status, "COMPLETED");
        const snapshot = await mesh.get(handle.executionId);
        assert.equal((snapshot.variables as any).discount.rate, 0.2);
      },
    );
  });

  it("hands the step's input to the function", async () => {
    const seen: unknown[] = [];

    await withWorker(
      (customer) => {
        seen.push(customer);
        return { rate: 0.05 };
      },
      async () => {
        await mesh.execute("discount_check", { tier: "silver" });
        assert.deepEqual(seen, [{ tier: "silver" }]);
      },
    );
  });

  it("awaits a capability that returns a promise", async () => {
    await withWorker(
      async (customer) => {
        await new Promise((wake) => setTimeout(wake, 20));
        return { rate: 0.5 };
      },
      async () => {
        const handle = await mesh.execute("discount_check", { tier: "gold" });
        const snapshot = await mesh.get(handle.executionId);

        assert.equal((snapshot.variables as any).discount.rate, 0.5);
      },
    );
  });

  it("fails the step when the capability declares a failure", async () => {
    await withWorker(
      () => {
        throw new CapabilityFailure("billing.no_such_tier", "unknown tier");
      },
      async () => {
        const handle = await mesh.execute("discount_check", { tier: "platinum" });
        assert.equal(handle.status, "FAILED");
      },
    );
  });

  it("still answers when the capability throws something unplanned", async () => {
    await withWorker(
      (customer) => (customer as any).missing.field,
      async () => {
        const handle = await mesh.execute("discount_check", { tier: "gold" });

        assert.equal(handle.status, "FAILED",
          "an execution must not wait forever for a reply that is never coming");
      },
    );
  });

  it("reports a capability this worker does not serve", async () => {
    await withWorker(
      () => ({}),
      async () => {
        const handle = await mesh.execute("discount_check", { tier: "gold" });
        assert.equal(handle.status, "FAILED");
      },
      "something_else",
    );
  });
});
