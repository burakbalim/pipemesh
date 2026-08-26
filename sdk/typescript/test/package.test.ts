/**
 * What somebody gets when they install this, rather than what the other tests
 * import from source.
 *
 * The suite reaches into `../src` directly, so it exercises every line of the
 * client without ever loading the package the way `require("@pipemesh/client")`
 * does. That gap hid a `main` pointing at a file the build has never produced:
 * every test passed, and the package could not be imported at all.
 */

import { describe, it } from "node:test";
import * as assert from "node:assert/strict";
import * as fs from "node:fs";
import * as path from "node:path";

const PACKAGE_ROOT = path.resolve(__dirname, "..", "..");
const manifest = require(path.join(PACKAGE_ROOT, "package.json"));

describe("the published package", () => {
  it("loads through the entry point it declares", () => {
    const entry = path.join(PACKAGE_ROOT, manifest.main);
    assert.ok(fs.existsSync(entry), `main points at ${manifest.main}, which the build does not produce`);

    const client = require(entry);
    assert.ok(client.PipeMesh, "the entry point does not export PipeMesh");
    assert.ok(client.PipeMeshWorker, "the entry point does not export PipeMeshWorker");
  });

  it("ships the types it declares", () => {
    assert.ok(fs.existsSync(path.join(PACKAGE_ROOT, manifest.types)),
      `types points at ${manifest.types}, which the build does not produce`);
  });

  /**
   * The worker resolves this at runtime rather than compiling it in, so a
   * package missing it installs cleanly and fails on first use.
   */
  it("ships the proto the worker reads at runtime", () => {
    assert.ok(fs.existsSync(path.join(PACKAGE_ROOT, "dist", "proto", "pipemesh.proto")),
      "the proto is missing, so serving a capability would fail after install");
  });

  it("publishes the source and not the tests", () => {
    const shipped: string[] = manifest.files;
    assert.ok(shipped.includes("dist/src"), "the compiled client is not in `files`");
    assert.ok(!shipped.includes("dist"),
      "`dist` ships the test directory too, which is not part of what is published");
  });
});
