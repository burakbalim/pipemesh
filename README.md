# PipeMesh

**A language-agnostic declarative runtime for AI workflows, implemented in Java.**

A declarative, model-agnostic runtime for building composable AI workflows. Provider-independent
and durable by design: workflows survive process failures, resume where they left off, and stay
observable end to end.

PipeMesh treats the workflow itself as a first-class, versioned, declarative artifact. Application
behavior is described in JSON; the runtime interprets that definition and executes it through
pluggable model providers, capabilities and tools.

```text
Workflow JSON  →  WHAT should happen
Runtime        →  HOW it happens
```

> **Status:** v0.1, first slice complete. A workflow described in a configuration directory runs end
> to end — model, condition, capability over MCP, human approval — survives a process restart, and
> reports itself to any OTLP backend. See [DESIGN.md](DESIGN.md) for the full architecture.

---

## Why

Most AI frameworks focus on the model or agent layer and let the LLM own the control flow.
PipeMesh takes the opposite position:

> **AI decides where AI is useful. The workflow engine decides how execution proceeds.**

Deterministic operations stay deterministic code. LLMs are used for reasoning and natural-language
interpretation — not for orchestration.

## Core concepts

| Primitive | Role |
|---|---|
| **Workflow** | Versioned JSON definition of the execution graph |
| **Step** | A unit of execution: `llm`, `capability`, `condition`, `approval`, `parallel`, `transform`, `wait` |
| **Capability** | A named unit of work — backed by MCP, REST, gRPC, an in-process function or an external worker |
| **Provider** | Pluggable model / messaging backend |
| **Execution state** | Persisted, observable, resumable |

A workflow names a capability and nothing more. Ownership, version, deployment mechanism and
permissions live in the capability registration, never in the workflow step (§9.8).

## Architecture at a glance

```text
                    User Input
                        │
                        ▼
                ┌────────────────┐
                │ Intent Resolver│
                └───────┬────────┘
                        │
                        ▼
                ┌───────────────┐
                │ Flow Registry │
                └───────┬───────┘
                        │
                        ▼
                ┌────────────────┐
                │ Workflow Engine│
                └───────┬────────┘
                        │
        ┌───────────────┼───────────────────┐
        ▼               ▼                   ▼
      Model         Capability          Approval
     Provider        Registry            Gateway
        │               │                   │
        ▼               ▼                   ▼
       LLM         MCP / API /        Human / System
                     Service
```

## Stack

The runtime is implemented in **Java 21**; the workflows it runs are not tied to any language.

| Layer | Technology |
|---|---|
| Core runtime | Java 21, framework-free (no Spring dependency in `core/`) |
| Build | Maven, multi-module |
| Client boundary | gRPC in `pipemesh-grpc`, generated from `proto/pipemesh.proto` |
| SDKs | Python (`sdk/python`) today; TypeScript and Java from the same proto next |
| Spring integration | `pipemesh-spring-boot-starter` (optional, separate module) |
| Workflow definitions | JSON — authored and versioned independently of all of the above |

"Language-agnostic" means three things: a workflow is a JSON artifact that no runtime language leaks
into, any language can drive the runtime over gRPC, and capabilities may be implemented in any language
behind the capability provider boundary. Java callers may skip the network entirely and embed the runtime
as a library.

```text
     Python SDK    Java SDK    TypeScript SDK
          └────────────┼────────────┘
                     gRPC
                       ▼
              PipeMesh Runtime (Java)
                       │
          ┌────────────┼────────────┐
       Workflow      State      Providers
                                   │
                              ┌────┴────┐
                             LLM       MCP
```

Three concepts stay separate throughout the design (§26.2):

```text
Runtime   →  the engine: workflow execution, state, scheduling
SDK       →  how a developer reaches the runtime
Provider  →  how the runtime reaches the outside world
```

LangChain, OpenAI, MCP and managed agent platforms are all *providers* — optional adapters, never
dependencies of the core.

## Deployment

```text
Embedded   Java app runs the runtime in its own JVM
Remote     any language reaches a runtime process over gRPC (docker run pipemesh/runtime)
Shared     several applications, several languages, one runtime deployment
```

Business logic never moves into the runtime. A workflow may *name* a capability, never carry code:
implementations live in the application or worker that registered them (§23.1).

See [DESIGN.md §26](DESIGN.md) for the boundary, the deployment modes and how the runtime invokes
work that lives inside an SDK.

## Observability

Execution telemetry leaves the runtime through one narrow observer, so several backends can run at
once and none of them can fail a workflow:

```java
ExecutionObserver observer = CompositeExecutionObserver.of(
        new OpenTelemetryExecutionObserver(openTelemetry),
        new LoggingExecutionObserver());

new WorkflowExecutor(stateStore, executors, observer);
```

Datadog, New Relic, Grafana and Honeycomb all ingest OTLP, so reaching them is configuration rather
than code — point the exporter at the collector they give you:

```bash
OTEL_EXPORTER_OTLP_ENDPOINT=https://otlp.eu01.nr-data.net:4317   # or Datadog, Grafana, ...
OTEL_EXPORTER_OTLP_HEADERS=api-key=...
```

Every span and metric is labelled with the organization the execution belongs to. And an execution
that waits three days for an approval and finishes in another process still reads as **one trace**:
the trace context is persisted with the state and picked up again on resume.

| Signal | What it answers |
|---|---|
| `pipemesh.workflow.executions` | how many finished, and in which status |
| `pipemesh.workflow.duration` | how long they took, waits included |
| `pipemesh.step.duration` | which step is slow |
| `pipemesh.approval.wait_time` | how long people take to decide |
| `pipemesh.llm.input_tokens` / `output_tokens` | what the models cost |

## Planned project structure

```text
core/           # workflow, execution, state, scheduler, expressions   (Java)
providers/      # messaging, models, tools                            (Java)
integrations/   # mcp, http, messaging                                (Java)
registry/       # workflow, capability, prompt, model                      (Java)
observability/  # tracing, metrics, logging                           (Java)
opentelemetry/  # spans and metrics for any OTLP backend              (Java)
spring/         # optional Spring Boot starter                        (Java)
proto/          # pipemesh.proto — the API contract                   (language-neutral)
grpc/           # the service, generated from the proto                (Java)
sdk/python/     # client SDK + capability worker                           (Python)
sdk/typescript/ # client SDK + capability worker                           (TypeScript)
sdk/java/       # remote client (embedding the library is the alternative)
schemas/        # workflow / capability / model JSON schemas               (language-neutral)
examples/       # simple-chat, tool-calling, approval-flow, parallel-flow
```

## Roadmap

- **Phase 1** ✅ — Workflow JSON, LLM step, condition, capability step, human approval, durable
  state, resume, MCP, observability. Intent resolution is the one Phase 1 item still open.
- **Phase 2** — MCP, structured output, retry, timeout, streaming, prompt registry
- **Phase 3** — Persistent state, human approval, resume, parallel execution, event-driven execution
- **Phase 4** — OpenTelemetry, workflow versioning, evaluation, cost tracking, model routing,
  distributed workers

## Success criteria

Adding a new AI behavior must require **configuration and composition, not runtime changes**:

```text
new-workflow.json + new-prompt.md + new-capability.json
```

with no edits to `WorkflowExecutor`, the scheduler, model providers, MCP integration, state
management or observability. If the engine has to change, the abstraction is leaking.

## Documentation

- [DESIGN.md](DESIGN.md) — full technical architecture & design document (47 sections)

## License

Licensed under the [Apache License 2.0](LICENSE).
