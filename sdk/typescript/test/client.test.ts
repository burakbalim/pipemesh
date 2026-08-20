/**
 * What a Node application sees when it talks to a PipeMesh runtime.
 *
 * The runtime is the real Java one, started in a child process and reached over
 * a socket. A mocked stub would only confirm the client calls itself the way it
 * was written.
 */

import { after, before, describe, it } from "node:test";
import * as assert from "node:assert/strict";
import { spawn, ChildProcess } from "node:child_process";
import * as fs from "node:fs";
import * as path from "node:path";
import * as readline from "node:readline";

import { ExecutionHandle, PipeMesh, PipeMeshError, isTerminal, isWaiting } from "../src/client";

const REPO = path.resolve(__dirname, "..", "..", "..", "..");
const CLASSPATH_FILE = path.join(REPO, "pipemesh-grpc", "target", "test-classpath.txt");
const CLASSES = [
  path.join(REPO, "pipemesh-grpc", "target", "classes"),
  path.join(REPO, "pipemesh-grpc", "target", "test-classes"),
];

let runtime: ChildProcess;
let mesh: PipeMesh;

function classpath(): string {
  if (!fs.existsSync(CLASSPATH_FILE)) {
    throw new Error(
      "run `mvn -pl pipemesh-grpc dependency:build-classpath " +
        "-Dmdep.outputFile=target/test-classpath.txt` first",
    );
  }
  return [...CLASSES, fs.readFileSync(CLASSPATH_FILE, "utf8").trim()].join(":");
}

function startRuntime(): Promise<string> {
  runtime = spawn("java", ["-cp", classpath(), "io.pipemesh.grpc.TestRuntimeServer"], {
    stdio: ["ignore", "pipe", "ignore"],
  });

  return new Promise((resolve, reject) => {
    const lines = readline.createInterface({ input: runtime.stdout! });
    lines.once("line", (line) => {
      const port = Number(line.trim());
      if (!Number.isInteger(port)) {
        reject(new Error(`the runtime did not report a port, said: ${line}`));
        return;
      }
      resolve(`localhost:${port}`);
    });
    runtime.once("error", reject);
  });
}

async function expensive(): Promise<ExecutionHandle> {
  return mesh.execute("venue_booking", { price: 250 });
}

describe("PipeMesh client", () => {
  before(async () => {
    const address = await startRuntime();
    mesh = new PipeMesh(address, { organization: "acme" });
  });

  after(() => {
    mesh?.close();
    runtime?.kill();
  });

  it("runs a workflow and stops at the approval", async () => {
    const handle = await expensive();

    assert.equal(handle.status, "WAITING");
    assert.ok(isWaiting(handle.status));
    assert.equal(handle.currentStep, "approval");
    assert.ok(handle.executionId.length > 0);
  });

  it("takes the other branch without asking anyone", async () => {
    const handle = await mesh.execute("venue_booking", { price: 10 });

    assert.equal(handle.status, "COMPLETED");
    assert.ok(isTerminal(handle.status));
  });

  it("reads back the variables it sent", async () => {
    const handle = await expensive();

    const snapshot = await mesh.get(handle.executionId);

    assert.equal(snapshot.organization, "acme");
    assert.equal(snapshot.workflowId, "venue_booking");
    assert.deepEqual((snapshot.variables as any).input, { price: 250 });
  });

  it("finishes the execution once approved", async () => {
    const waiting = await expensive();

    const finished = await mesh.approve(
      waiting.executionId,
      `${waiting.executionId}:approval`,
      "burak",
    );

    assert.equal(finished.status, "COMPLETED");
    assert.equal(finished.currentStep, "booked");
  });

  it("cancels the execution when rejected", async () => {
    const waiting = await expensive();

    const finished = await mesh.reject(waiting.executionId, `${waiting.executionId}:approval`);

    assert.equal(finished.status, "CANCELLED");
  });

  it("changes nothing when the same decision arrives twice", async () => {
    const waiting = await expensive();
    const approvalId = `${waiting.executionId}:approval`;

    const first = await mesh.approve(waiting.executionId, approvalId);
    const second = await mesh.approve(waiting.executionId, approvalId);

    assert.equal(first.status, "COMPLETED");
    assert.equal(second.status, "COMPLETED");
  });

  it("opens the watch with where the execution already is", async () => {
    const waiting = await expensive();

    const updates = mesh.watch(waiting.executionId)[Symbol.asyncIterator]();
    const first = await updates.next();

    assert.equal(first.value.kind, "started");
    assert.equal(first.value.sequence, 0);
    assert.equal(first.value.status, "WAITING");

    await updates.return?.();
  });

  it("ends the watch when the execution ends", async () => {
    const waiting = await expensive();

    const updates = mesh.watch(waiting.executionId)[Symbol.asyncIterator]();
    await updates.next();

    await mesh.approve(waiting.executionId, `${waiting.executionId}:approval`);

    const kinds: string[] = [];
    for (let next = await updates.next(); !next.done; next = await updates.next()) {
      kinds.push(next.value.kind);
    }

    assert.equal(kinds.at(-1), "finished");
    assert.ok(kinds.includes("stepFinished"));
  });

  it("says which execution is missing", async () => {
    await assert.rejects(
      () => mesh.get("no-such-execution"),
      (failure: PipeMeshError) => failure.notFound,
    );
  });

  it("says which workflow is missing", async () => {
    await assert.rejects(
      () => mesh.execute("no_such_workflow"),
      (failure: PipeMeshError) => failure.notFound,
    );
  });

  it("reads a message and runs what it asked for", async () => {
    const handle = await mesh.process("please book a venue for Friday", { price: 250 });

    assert.equal(handle.status, "WAITING");
    assert.equal(handle.currentStep, "approval");
  });

  it("records which intent started it", async () => {
    const handle = await mesh.process("book a venue", { price: 250 });

    const snapshot = await mesh.get(handle.executionId);
    const intent = (snapshot.variables as any).intent;

    assert.equal(intent.id, "book_venue");
    assert.equal(intent.resolvedBy, "deterministic");
  });

  it("says when it could not tell what was meant", async () => {
    await assert.rejects(
      () => mesh.process("what is the weather like in Antalya"),
      (failure: PipeMeshError) => failure.failedPrecondition,
    );
  });
});
