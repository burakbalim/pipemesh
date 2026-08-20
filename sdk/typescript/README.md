# @pipemesh/client

A TypeScript client for a running PipeMesh runtime. It does not execute workflows — the runtime does
that, and an SDK's job is to reach it (DESIGN.md §26.2).

```ts
import { PipeMesh, isWaiting } from "@pipemesh/client";

const mesh = new PipeMesh("localhost:8080", { organization: "acme" });

const handle = await mesh.execute("venue_booking", { price: 250 });

if (isWaiting(handle.status)) {
  await mesh.approve(handle.executionId, `${handle.executionId}:approval`, "burak");
}
```

`execute` resolves as soon as the execution stops moving — finished, or waiting for a person. A
workflow that waits three days does not hold the call open for three days.

## Watching one as it runs

```ts
for await (const update of mesh.watch(handle.executionId)) {
  if (update.kind === "token") process.stdout.write(update.text ?? "");
}
```

The subscription opens when `watch()` is called, not when you first read from it — a lazy
subscription would miss whatever happened in between, silently. The first item is always
`kind === "started"` with the status as of that moment. Leaving the loop early hangs up: breaking out
cancels the call, so neither the server nor your process is left holding a stream nobody reads.

## Errors

```ts
try {
  await mesh.get("no-such-execution");
} catch (failure) {
  if (failure instanceof PipeMeshError && failure.notFound) { /* ... */ }
}
```

The gRPC status code is kept, because the useful question after a failure is whether it was this
caller's mistake or the server's — and the answer decides whether retrying makes sense.

## No codegen

The proto is loaded at runtime by `@grpc/proto-loader`, and `npm run build` copies
`proto/pipemesh.proto` into the package. Installing this needs no protoc.

## Tests

They start the real Java runtime in a child process and talk to it over a socket.

```bash
mvn -pl pipemesh-grpc -am -DskipTests install
mvn -pl pipemesh-grpc dependency:build-classpath -Dmdep.outputFile=target/test-classpath.txt
npm install && npm test
```
