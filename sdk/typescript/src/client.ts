/**
 * A client for a running PipeMesh runtime.
 *
 * It does not execute workflows. The runtime does that, and an SDK's job is to
 * reach it (DESIGN.md §26.2).
 */

import * as path from "node:path";
import * as grpc from "@grpc/grpc-js";
import * as protoLoader from "@grpc/proto-loader";

import { fromStruct, toStruct } from "./structs";

/**
 * The proto is loaded at runtime rather than compiled into generated classes,
 * so installing this package needs no codegen step.
 *
 * It is copied into the package at build time from the one at the repository
 * root — the same file the Java service is built from. Reaching up out of the
 * package instead would work in this repository and break the moment anyone
 * installed it from npm.
 */
const PROTO_PATH = path.resolve(__dirname, "..", "proto", "pipemesh.proto");

const definition = protoLoader.loadSync(PROTO_PATH, {
  keepCase: false,
  longs: Number,
  enums: String,
  defaults: true,
  oneofs: true,
});

const proto = grpc.loadPackageDefinition(definition) as unknown as {
  pipemesh: { v1: { PipeMesh: grpc.ServiceClientConstructor } };
};

/** Where an execution stands. */
export type ExecutionStatus =
  | "CREATED"
  | "RUNNING"
  | "WAITING"
  | "COMPLETED"
  | "FAILED"
  | "CANCELLED";

export function isWaiting(status: ExecutionStatus): boolean {
  return status === "WAITING";
}

export function isTerminal(status: ExecutionStatus): boolean {
  return status === "COMPLETED" || status === "FAILED" || status === "CANCELLED";
}

export interface ExecutionHandle {
  executionId: string;
  status: ExecutionStatus;
  currentStep?: string;
}

export interface ExecutionSnapshot {
  executionId: string;
  organization: string;
  workflowId: string;
  workflowVersion: string;
  status: ExecutionStatus;
  currentStep?: string;
  variables: Record<string, unknown>;
}

export interface Approval {
  approvalId: string;
  approved: boolean;
  decidedBy?: string;
  comment?: string;
}

/** Something that happened to an execution being watched. */
export interface Update {
  sequence: number;
  kind:
    | "started"
    | "stepStarted"
    | "stepFinished"
    | "suspended"
    | "resumed"
    | "recovered"
    | "finished"
    | "token";
  stepId?: string;
  text?: string;
  status?: ExecutionStatus;
  /** Counts from 1 on `stepStarted`: a retry is a real second start. */
  attempt?: number;
  /** On `recovered`: whether the step could be run again, or stopped for a person. */
  repeated?: boolean;
  reason?: string;
}

/** What a watcher can decline. Status updates are not on the list, deliberately. */
export interface WatchOptions {
  /** Model output as it is produced. Defaults to on. */
  tokens?: boolean;
  /** `stepStarted`, which roughly doubles the number of updates. Defaults to on. */
  progress?: boolean;
  /**
   * How many step history entries you have already seen; everything after them
   * is replayed before the live stream begins.
   *
   * A count rather than a sequence number: a sequence belongs to one stream,
   * while step history is durable and ordered, so "the first N" means the same
   * thing whichever replica answers. Tokens are not replayed — they are never
   * stored.
   */
  fromStep?: number;
}

/**
 * A call the runtime refused.
 *
 * The status code is kept because the useful question after a failure is whether
 * it was this caller's mistake or the server's, and the answer decides whether
 * retrying makes any sense.
 */
export class PipeMeshError extends Error {
  readonly code: grpc.status;

  constructor(message: string, code: grpc.status) {
    super(message);
    this.name = "PipeMeshError";
    this.code = code;
  }

  get notFound(): boolean {
    return this.code === grpc.status.NOT_FOUND;
  }

  get invalid(): boolean {
    return this.code === grpc.status.INVALID_ARGUMENT;
  }

  get unimplemented(): boolean {
    return this.code === grpc.status.UNIMPLEMENTED;
  }

  /** The call was fine; the runtime could not act on it as asked. */
  get failedPrecondition(): boolean {
    return this.code === grpc.status.FAILED_PRECONDITION;
  }
}

export interface PipeMeshOptions {
  organization?: string;
  credentials?: grpc.ChannelCredentials;
}

export class PipeMesh {
  private readonly client: grpc.Client;
  private readonly organization: string;

  constructor(target = "localhost:8080", options: PipeMeshOptions = {}) {
    this.organization = options.organization ?? "default";
    this.client = new proto.pipemesh.v1.PipeMesh(
      target,
      options.credentials ?? grpc.credentials.createInsecure(),
    );
  }

  /**
   * Run a named workflow.
   *
   * Resolves as soon as the execution stops moving — finished, or waiting for a
   * person. Waiting costs nothing on the server, so a workflow that needs an
   * approval resolves promptly with a waiting status rather than holding the
   * call open (DESIGN.md §26.4).
   */
  execute(
    workflowId: string,
    input: Record<string, unknown> = {},
    options: { organization?: string; traceparent?: string; version?: string } = {},
  ): Promise<ExecutionHandle> {
    return this.unary<ExecutionHandle>("StartExecution", {
      workflowId,
      input: toStruct(input),
      organizationId: options.organization ?? this.organization,
      traceparent: options.traceparent ?? "",
      // Empty means "newest", chosen once at the start and written to the
      // execution's record — a deploy mid-run does not move it (§24).
      workflowVersion: options.version ?? "",
    }, toHandle);
  }

  /**
   * Let the runtime read the message and run whatever it asks for.
   *
   * Rejects with `FAILED_PRECONDITION` when the message does not settle on an
   * intent. That is not a bad request: the call was fine, the runtime could not
   * tell what to do with it, and sending the same words again will not help.
   */
  process(
    message: string,
    input: Record<string, unknown> = {},
    options: { organization?: string; traceparent?: string } = {},
  ): Promise<ExecutionHandle> {
    return this.unary<ExecutionHandle>("ProcessMessage", {
      message,
      input: toStruct(input),
      organizationId: options.organization ?? this.organization,
      traceparent: options.traceparent ?? "",
    }, toHandle);
  }

  approve(executionId: string, approvalId: string, decidedBy = "", comment = ""): Promise<ExecutionHandle> {
    return this.decide(executionId, { approvalId, approved: true, decidedBy, comment });
  }

  reject(executionId: string, approvalId: string, decidedBy = "", comment = ""): Promise<ExecutionHandle> {
    return this.decide(executionId, { approvalId, approved: false, decidedBy, comment });
  }

  /**
   * Deliver a decision.
   *
   * Delivering the same one twice is safe: the runtime advances an execution
   * once and reports where it stands the second time.
   */
  decide(executionId: string, approval: Approval): Promise<ExecutionHandle> {
    return this.unary<ExecutionHandle>("SubmitApproval", {
      executionId,
      approvalId: approval.approvalId,
      approved: approval.approved,
      decidedBy: approval.decidedBy ?? "",
      comment: approval.comment ?? "",
    }, toHandle);
  }

  get(executionId: string): Promise<ExecutionSnapshot> {
    return this.unary<ExecutionSnapshot>("GetExecution", { executionId }, (reply: any) => ({
      executionId: reply.executionId,
      organization: reply.organizationId,
      workflowId: reply.workflowId,
      workflowVersion: reply.workflowVersion,
      status: reply.status.replace("EXECUTION_STATUS_", "") as ExecutionStatus,
      currentStep: reply.currentStepId || undefined,
      variables: fromStruct(reply.variables),
    }));
  }

  /**
   * Yield what happens to an execution, until it ends.
   *
   * The subscription opens when this is called, not when the caller first reads
   * from the iterator. A stream subscribed lazily would miss everything between
   * asking to watch and getting round to reading, and the loss would be silent.
   *
   * The first item is always `started` and carries the status as of that moment
   * — the point a caller can act from, knowing nothing after it will be missed.
   * The stream ends itself when the execution reaches a terminal status.
   *
   * `tokens` and `progress` turn off the two noisy parts. Status cannot be turned
   * off — a stream that could omit `finished` would leave a caller waiting for
   * something already over. Declining leaves gaps in `sequence`, which is how
   * filtering stays distinguishable from loss.
   */
  watch(executionId: string, options: WatchOptions = {}): AsyncIterable<Update> {
    const exclude: string[] = [];
    if (options.tokens === false) exclude.push("UPDATE_KIND_TOKEN");
    if (options.progress === false) exclude.push("UPDATE_KIND_PROGRESS");

    return streamOf(this.serverStream(executionId, exclude, options.fromStep ?? 0));
  }

  close(): void {
    this.client.close();
  }

  private serverStream(
    executionId: string,
    exclude: string[],
    fromStep: number,
  ): grpc.ClientReadableStream<unknown> {
    const method = (this.client as unknown as Record<string, Function>).WatchExecution;
    return method.call(this.client, {
      executionId,
      exclude,
      fromStep,
    }) as grpc.ClientReadableStream<unknown>;
  }

  private unary<T>(method: string, request: unknown, map: (reply: any) => T): Promise<T> {
    return new Promise((resolve, reject) => {
      const call = (this.client as unknown as Record<string, Function>)[method];
      call.call(this.client, request, (failure: grpc.ServiceError | null, reply: unknown) => {
        if (failure) {
          reject(new PipeMeshError(failure.details, failure.code));
          return;
        }
        resolve(map(reply));
      });
    });
  }
}

function toHandle(reply: any): ExecutionHandle {
  return {
    executionId: reply.executionId,
    status: reply.status.replace("EXECUTION_STATUS_", "") as ExecutionStatus,
    currentStep: reply.currentStepId || undefined,
  };
}

function toUpdate(message: any): Update {
  const kind = message.update as Update["kind"];
  switch (kind) {
    case "started":
      return {
        sequence: Number(message.sequence),
        kind,
        status: message.started.execution.status.replace("EXECUTION_STATUS_", ""),
      };
    case "stepStarted":
      return {
        sequence: Number(message.sequence),
        kind,
        stepId: message.stepStarted.stepId,
        attempt: Number(message.stepStarted.attempt),
      };
    case "recovered":
      return {
        sequence: Number(message.sequence),
        kind,
        stepId: message.recovered.stepId,
        repeated: message.recovered.repeated,
        reason: message.recovered.reason || undefined,
      };
    case "stepFinished":
      return { sequence: Number(message.sequence), kind, stepId: message.stepFinished.stepId };
    case "suspended":
      return { sequence: Number(message.sequence), kind, stepId: message.suspended.stepId };
    case "resumed":
      return { sequence: Number(message.sequence), kind, stepId: message.resumed.stepId };
    case "finished":
      return {
        sequence: Number(message.sequence),
        kind,
        status: message.finished.status.replace("EXECUTION_STATUS_", ""),
      };
    case "token":
      return {
        sequence: Number(message.sequence),
        kind,
        stepId: message.token.stepId,
        text: message.token.text,
      };
    default:
      return { sequence: Number(message.sequence), kind };
  }
}

/**
 * Wraps the gRPC stream as an async iterable that releases the call when the
 * reader stops.
 *
 * Leaving early has to hang up. A `break` out of a `for await`, or an iterator
 * simply dropped, calls `return()` — and without cancelling there, the
 * connection stays open, the server keeps a subscriber nobody reads, and a Node
 * process will not exit because something is still listening.
 */
function streamOf(call: grpc.ClientReadableStream<unknown>): AsyncIterable<Update> {
  return {
    [Symbol.asyncIterator](): AsyncIterator<Update> {
      const messages = (call as unknown as AsyncIterable<unknown>)[Symbol.asyncIterator]();

      return {
        async next(): Promise<IteratorResult<Update>> {
          try {
            const message = await messages.next();
            return message.done
              ? { done: true, value: undefined }
              : { done: false, value: toUpdate(message.value) };
          } catch (failure) {
            const error = failure as grpc.ServiceError;
            throw new PipeMeshError(error.details ?? String(failure), error.code);
          }
        },

        async return(): Promise<IteratorResult<Update>> {
          call.cancel();
          return { done: true, value: undefined };
        },
      };
    },
  };
}
