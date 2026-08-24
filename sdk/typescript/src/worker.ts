/**
 * Serves capabilities from inside a Node application.
 *
 * The runtime does not reach into this process; this process opens the
 * connection and the runtime pushes invocations down it, so a worker needs no
 * reachable address, certificate or firewall exception (DESIGN.md §26.1).
 */

import * as path from "node:path";
import * as grpc from "@grpc/grpc-js";
import * as protoLoader from "@grpc/proto-loader";

import { authMetadata, resolveKey } from "./credentials";
import { fromStruct, toStruct } from "./structs";

const PROTO_PATH = path.resolve(__dirname, "..", "proto", "pipemesh.proto");

const definition = protoLoader.loadSync(PROTO_PATH, {
  keepCase: false,
  longs: Number,
  enums: String,
  defaults: true,
  oneofs: true,
});

const proto = grpc.loadPackageDefinition(definition) as unknown as {
  pipemesh: { v1: { CapabilityWorker: grpc.ServiceClientConstructor } };
};

export type CapabilityFunction = (
  input: Record<string, unknown>,
) => unknown | Promise<unknown>;

/**
 * Thrown to fail a call with a code the workflow can branch on.
 *
 * `retryable` is false by default: a business rule that said no does not say
 * anything different when asked twice. Transport trouble is a different thing,
 * and the runtime classifies that itself.
 */
export class CapabilityFailure extends Error {
  readonly code: string;
  readonly retryable: boolean;

  constructor(code: string, message = "", retryable = false) {
    super(message);
    this.name = "CapabilityFailure";
    this.code = code;
    this.retryable = retryable;
  }
}

// How long to wait before reconnecting, doubling up to the cap. The common
// first failure is a deployment where the runtime is a few seconds behind this
// process; the common later one is the runtime restarting. Both look the same
// from here, and neither should end this worker.
const RETRY_MIN_MS = 500;
const RETRY_MAX_MS = 30_000;

export interface WorkerOptions {
  /** Identifies this worker, the same way it identifies a client. */
  apiKey?: string;
  organization?: string;
  credentials?: grpc.ChannelCredentials;
}

export class PipeMeshWorker {
  private readonly client: grpc.Client;
  private readonly organization: string;
  private readonly capabilities = new Map<string, CapabilityFunction>();
  private stream?: grpc.ClientDuplexStream<unknown, any>;
  private stopping = false;
  private retry?: NodeJS.Timeout;
  private readonly metadata: grpc.Metadata;

  constructor(target = "localhost:8080", options: WorkerOptions = {}) {
    this.organization = options.organization ?? "default";
    // A worker is a caller too. Its registration is bound to an organization
    // (§14), so a deployment that authenticates has to be able to tell which one
    // is connecting — an authenticated client with an anonymous worker would be
    // half a boundary.
    this.metadata = authMetadata(resolveKey(options.apiKey), options.credentials !== undefined);
    this.client = new proto.pipemesh.v1.CapabilityWorker(
      target,
      options.credentials ?? grpc.credentials.createInsecure(),
    );
  }

  /** Register a function under the name a capability registration uses. */
  capability(name: string, run: CapabilityFunction): this {
    this.capabilities.set(name, run);
    return this;
  }

  /**
   * Connect and serve until stopped, reconnecting on its own.
   *
   * A runtime that restarted, a proxy that closed an idle stream, and this
   * process simply having started first are the same event from here: the
   * connection went away and there are still capabilities to serve. Stopping at
   * the first failure would leave the application up and quietly unable to do
   * any work — an execution then waits at its capability step for a worker that
   * is never coming back.
   */
  start(): this {
    this.stopping = false;
    this.connect(RETRY_MIN_MS);
    return this;
  }

  private connect(delay: number): void {
    if (this.stopping) return;

    const stream = (this.client as unknown as Record<string, Function>).Connect.call(
      this.client,
      this.metadata,
    ) as grpc.ClientDuplexStream<unknown, any>;
    this.stream = stream;
    const openedAt = Date.now();

    stream.on("data", (invocation) => void this.answer(invocation));
    // A failed stream also emits "close", which is where the retry lives. Left
    // unhandled, this event would take the whole process down instead.
    stream.on("error", () => undefined);
    stream.on("close", () => this.reconnect(openedAt, delay));

    stream.write({
      registration: {
        organizationId: this.organization,
        capabilityIds: [...this.capabilities.keys()],
      },
    });
  }

  private reconnect(openedAt: number, delay: number): void {
    if (this.stopping) return;

    // A connection that lasted is proof the runtime is reachable, so the next
    // failure starts over rather than inheriting a long wait.
    const wait = Date.now() - openedAt > RETRY_MAX_MS ? RETRY_MIN_MS : delay;

    this.retry = setTimeout(() => this.connect(Math.min(wait * 2, RETRY_MAX_MS)), wait);
    // A pending retry is not a reason to keep the process alive.
    this.retry.unref?.();
  }

  stop(): void {
    this.stopping = true;
    if (this.retry) clearTimeout(this.retry);
    this.stream?.end();
    this.client.close();
  }

  private async answer(invocation: any): Promise<void> {
    const run = this.capabilities.get(invocation.capabilityId);

    if (!run) {
      this.reply(invocation.invocationId, {
        failure: {
          code: "worker.unknown_capability",
          message: `this worker does not serve '${invocation.capabilityId}'`,
          retryable: false,
        },
      });
      return;
    }

    try {
      const output = await run(fromStruct(invocation.input));
      this.reply(invocation.invocationId, { output: toStruct(asObject(output)) });
    } catch (failure) {
      this.reply(invocation.invocationId, { failure: describe(failure) });
    }
  }

  private reply(invocationId: string, result: Record<string, unknown>): void {
    this.stream?.write({ result: { invocationId, ...result } });
  }
}

/**
 * An exception the capability did not plan for is still an answer the runtime
 * needs. Letting it escape would leave the execution waiting for a reply that is
 * never coming.
 */
function describe(failure: unknown): Record<string, unknown> {
  if (failure instanceof CapabilityFailure) {
    return { code: failure.code, message: failure.message, retryable: failure.retryable };
  }
  const error = failure as Error;
  return {
    code: "worker.threw",
    message: `${error?.name ?? "Error"}: ${error?.message ?? String(failure)}`,
    retryable: false,
  };
}

/** A Struct has no shape without a named field, so a bare value gets one. */
function asObject(value: unknown): Record<string, unknown> {
  if (value === null || value === undefined) {
    return {};
  }
  if (typeof value === "object" && !Array.isArray(value)) {
    return value as Record<string, unknown>;
  }
  return { value };
}
