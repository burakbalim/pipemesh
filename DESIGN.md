# PipeMesh

## Technical Architecture & Design

**A language-agnostic declarative runtime for AI workflows, implemented in Java.**

**Implementation Language:** Java 21 (core, framework-free) — Python SDK for clients and capabilities

**Status:** Proposed
**Version:** 0.1
**Architecture Style:** Declarative Workflow + Event-Driven Execution + Pluggable Providers
**Primary Goal:** Build a model-agnostic, provider-agnostic runtime for deterministic and configurable AI workflows.

---

# 1. Executive Summary

Modern LLM applications increasingly require more than a single model invocation.

A production AI application may need to:

* classify user intent,
* execute multi-step workflows,
* invoke LLMs,
* call MCP tools,
* interact with internal services,
* request human approval,
* evaluate conditions,
* branch dynamically,
* execute steps in parallel,
* retry failed operations,
* maintain execution state,
* resume interrupted workflows,
* stream responses,
* switch between model providers,
* and provide complete execution traces.

Most AI frameworks primarily focus on the model or agent layer.

This project takes a different approach:

> **The workflow itself is a first-class, versioned, declarative artifact.**

Application behavior is described using a JSON-based workflow definition.

The runtime interprets this definition and executes it using pluggable providers and capabilities.

```text
                    User Input
                        │
                        ▼
                ┌───────────────┐
                │ Intent Resolver│
                └───────┬───────┘
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

The runtime is intentionally separated from workflow definitions.

This creates a clear boundary:

```text
Workflow JSON
    ↓
WHAT should happen

Runtime
    ↓
HOW it happens
```

---

# 2. Design Goals

## 2.1 Primary Goals

The runtime MUST provide:

1. Model provider abstraction.
2. Messaging protocol abstraction.
3. Declarative workflow definitions.
4. Intent-to-workflow resolution.
5. Capability/tool abstraction.
6. MCP integration.
7. Conditional execution.
8. Human-in-the-loop operations.
9. Parallel execution.
10. Retry and timeout policies.
11. Persistent execution state.
12. Workflow resumability.
13. Streaming.
14. Structured LLM output.
15. Execution tracing.
16. Versioned prompts.
17. Model configuration independent from workflows.
18. Deterministic execution wherever possible.
19. Extensibility without modifying the core engine.

---

# 3. Non-Goals

The runtime is NOT intended to:

* become another general-purpose programming language,
* replace Kubernetes,
* replace a message broker,
* hide all model-specific capabilities,
* make every operation LLM-driven,
* turn every business rule into an LLM decision,
* require MCP for every external operation,
* execute arbitrary code embedded inside a workflow definition,
* host the application's business logic.

The architecture explicitly favors deterministic code for deterministic operations.

Business logic belongs to the application, not to the runtime. The runtime knows *when* a capability
should run, never *what* it does internally (§9.8, §23).

---

# 4. Core Architectural Principle

The most important principle is:

> **AI decides where AI is useful. The workflow engine decides how execution proceeds.**

The LLM should not own the entire application control flow.

Bad:

```text
User
 ↓
LLM
 ↓
LLM decides everything
 ↓
LLM
 ↓
Tool
 ↓
LLM
```

Preferred:

```text
Workflow
 ↓
Deterministic step
 ↓
LLM
 ↓
Structured result
 ↓
Deterministic condition
 ↓
Capability
 ↓
Approval
 ↓
Capability
```

This distinction makes the system significantly easier to test, observe, secure, and reason about.

---

# 5. High-Level Architecture

```text
┌─────────────────────────────────────────────────────────────┐
│                         Application                         │
│                                                             │
│  HTTP / WebSocket / Messaging / CLI / SDK                  │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                    AI Workflow Runtime                      │
│                                                             │
│ ┌───────────────┐    ┌───────────────────────────────────┐ │
│ │ Intent        │───▶│          Workflow Engine           │ │
│ │ Resolver      │    │                                   │ │
│ └───────────────┘    │  State Machine                    │ │
│                      │  Scheduler                        │ │
│                      │  Step Executor                    │ │
│                      │  Condition Evaluator              │ │
│                      └───────────────┬───────────────────┘ │
│                                      │                     │
│         ┌──────────────────┼────────────────────┐          │
│         ▼                  ▼                    ▼          │
│   Model Registry   Capability Registry    Prompt Registry   │
│         │                  │                    │          │
└─────────┼──────────────────┼────────────────────┼──────────┘
          │                  │                    │
          ▼                  ▼                    ▼
   OpenAI / Claude   MCP / REST / DB         Prompt Files
   Local / vLLM      Internal Services
```

---

# 6. Architectural Layers

## 6.1 Configuration Layer

Defines:

* model providers,
* models,
* protocols,
* intents,
* workflows,
* prompts,
* capabilities,
* execution policies.

No execution logic lives here.

---

## 6.2 Runtime Layer

Responsible for:

* parsing workflows,
* validating schemas,
* managing execution state,
* scheduling steps,
* evaluating conditions,
* invoking providers,
* handling failures,
* resuming workflows.

---

## 6.3 Integration Layer

Provides adapters for:

* LLM providers,
* MCP,
* HTTP,
* internal services,
* databases,
* messaging systems,
* human approval systems.

---

## 6.4 Observability Layer

Responsible for:

* traces,
* execution history,
* token usage,
* latency,
* errors,
* model calls,
* tool calls,
* workflow metrics.

---

# 7. Workflow as a State Machine

Every workflow is represented internally as a state machine.

Example:

```text
START
  │
  ▼
EXTRACT_EVENT
  │
  ▼
VALIDATE
  │
  ├──────── invalid ────────▶ REQUEST_CLARIFICATION
  │
  ▼
SEARCH_VENUE
  │
  ▼
CHECK_APPROVAL
  │
  ├──────── rejected ───────▶ CANCELLED
  │
  ▼
CREATE_EVENT
  │
  ▼
DONE
```

The JSON representation is only the serialized form.

Internally the runtime should compile it into an immutable execution graph.

```text
WorkflowDefinition
        │
        ▼
WorkflowCompiler
        │
        ▼
ExecutionGraph
        │
        ▼
WorkflowExecutor
```

This separation is important.

The runtime should not repeatedly interpret raw JSON during execution.

---

# 8. Workflow Definition

Example:

```json
{
  "id": "create_event",
  "version": "1.0",

  "entry": "extract_event",

  "steps": [
    {
      "id": "extract_event",
      "type": "llm",
      "model": "fast",
      "prompt": "event_extraction",
      "output": "event"
    },

    {
      "id": "validate",
      "type": "condition",
      "expression": "$.event.valid == true",
      "onTrue": "search_venue",
      "onFalse": "clarification"
    },

    {
      "id": "search_venue",
      "type": "capability",
      "capability": "venue_search",
      "input": "$.event.location",
      "output": "venues"
    },

    {
      "id": "approval",
      "type": "human_approval",
      "message": "Create this event?",
      "onApproved": "create_event",
      "onRejected": "cancelled"
    },

    {
      "id": "create_event",
      "type": "capability",
      "capability": "event_creation",
      "input": "$.event",
      "output": "createdEvent"
    }
  ]
}
```

---

# 9. Workflow Step Types

The initial runtime should support a small set of primitives.

## 9.1 LLM

Invokes a configured model.

```json
{
  "type": "llm",
  "model": "reasoning",
  "prompt": "event_analysis",
  "output": "analysis"
}
```

---

## 9.2 Capability

Invokes an abstract capability.

```json
{
  "type": "capability",
  "capability": "venue_search"
}
```

The workflow should never need to know whether the capability is implemented using MCP, REST, Java code, or another mechanism.

---

## 9.3 Condition

Evaluates deterministic logic.

```json
{
  "type": "condition",
  "expression": "$.event.price > 0",
  "onTrue": "approval",
  "onFalse": "create"
}
```

---

## 9.4 Human Approval

Suspends the workflow until an external decision arrives.

```json
{
  "type": "human_approval",
  "message": "Approve this action?",
  "onApproved": "execute",
  "onRejected": "cancel"
}
```

The workflow becomes resumable rather than blocking a thread.

---

## 9.5 Parallel

Runs independent branches concurrently.

```json
{
  "type": "parallel",
  "branches": [
    "search_venues",
    "load_user_preferences",
    "load_weather"
  ],
  "join": "build_recommendation"
}
```

---

## 9.6 Transform

Performs deterministic data transformation.

```json
{
  "type": "transform",
  "operation": "merge",
  "inputs": [
    "$.venues",
    "$.preferences"
  ],
  "output": "recommendationContext"
}
```

---

## 9.7 Wait

Suspends an execution until something outside it happens.

```json
{
  "type": "wait",
  "event": "payment_completed",
  "correlationKey": "$.order.id",
  "output": "payment",
  "next": "ship",
  "timeoutSeconds": 3600,
  "onTimeout": "chase_payment"
}
```

### The matching problem

Approval and wait share all their suspension machinery, and differ in exactly one place: who
knows the address. An approver holds the `approvalId` the runtime handed out. A payment service
holds no such thing — it knows an order number and nothing about executions.

So a wait is filed under a **key computed from the variables at the moment of suspension**:

```text
suspending:  (organization, "payment_completed", $.order.id → "A-4172")  is recorded
publishing:  (organization, "payment_completed", "A-4172")  is looked up  →  execution found
```

The key is frozen at suspend time. If the variable later changes, the wait still stands where it
was filed — the alternative, recomputing on publish, would mean an execution's address depends on
work it did after it stopped.

**The organization is part of the key, not a filter applied afterwards.** One tenant publishing an
event must not advance another tenant's execution (§22.2), and a boundary enforced by remembering
to add a `WHERE` clause is a boundary that eventually leaks.

**A publish reaches every matching execution, not the first.** Two orders may wait on the same
event; stopping at the first is silently forgetting the rest.

**A key that cannot be computed is refused.** If the correlation path is absent from the variables,
the step fails rather than filing itself under an empty key — that would be an execution nobody can
ever reach and nobody notices, which is worse than a failure.

### Bounded by choice

`timeoutSeconds` is optional, and an omitted one means the wait is genuinely unbounded. That is
allowed, but only as something someone wrote down: an execution stuck in `WAITING` forever is the
same failure as an unbounded step budget or an unbounded agent loop (§9.9, §17), and those are
bounded here for the same reason. When the deadline passes, the execution branches to `onTimeout`,
or fails if there is nowhere to go.

Expiry is collected by the pass that already runs for orphan recovery (§38) rather than a second
scheduler — one thing to deploy, one thing to forget to deploy.

---

## 9.8 Decision — Capability, Not Task

Business code owned by the calling application must be reachable from a workflow. An earlier draft
introduced a separate `task` step type for it. That is rejected.

> **A workflow must not encode the deployment mechanism or the implementation ownership of a
> capability.** Whether a capability is implemented as an MCP tool, a REST endpoint, a gRPC service,
> an in-process function or an external worker is a runtime concern.
>
> Therefore `task` is **not** a workflow-level step type. Workflows invoke everything through the
> uniform capability abstraction.
>
> `Task` may still exist as an internal runtime concept — an executable unit the engine schedules —
> but it must not leak into the workflow DSL.
>
> Ownership, versioning, deployment model and permissions remain metadata of the capability
> registration rather than properties of the workflow step.

So a workflow says only this, for business code and external tools alike:

```json
{
  "type": "capability",
  "capability": "calculate_discount"
}
```

The registry — not the workflow — knows the difference:

```text
Workflow
   │
   ▼
Capability
   │
   ├── MCP
   ├── REST
   ├── gRPC
   ├── Java function
   └── Python worker
```

The two vocabularies stay on opposite sides of the boundary:

```text
Workflow Step
      ↓
Capability Invocation     ← what the workflow expresses
      ↓
Execution Task            ← what the runtime schedules
```

```text
Capability  →  a capability the workflow invokes
Task        →  an execution unit the runtime runs
```

This is what keeps §10's rule intact as the list of supported backends grows — MCP, REST, gRPC,
Java, Python, Go, a Kubernetes Job, a durable-execution engine, a serverless function. Every one of
them is a registration detail. None of them is a workflow concept.

---

## 9.9 Agent Loop

Some work cannot be laid out as a fixed graph in advance: the model has to look at a result and
decide whether to call another tool. That is a real need, and refusing it would push users back into
writing their own loop outside the runtime.

The loop is therefore a **step**, not a mode of the engine:

```json
{
  "type": "agent",
  "model": "reasoning",
  "prompt": "research.investigate.v1",
  "capabilities": ["search_docs", "read_page"],
  "maxIterations": 8,
  "output": "findings",
  "next": "summarize"
}
```

Inside that step the model may iterate — call a capability, read the result, call another. Outside
it, nothing changes: the step produces a value and the workflow continues along the edge the
definition declares.

The bounds are what make it safe to have at all:

```text
the model chooses    →  which of the listed capabilities to call, and when to stop
the workflow chooses →  that this step runs, what it may reach, when it must stop,
                        and what happens next
```

* the capability list is declared in the step, not discovered by the model,
* `maxIterations` is mandatory — an unbounded loop is not a workflow,
* every turn is recorded on the step's history entry — which capability was
  reached, what came back, what the whole loop cost — so the loop stays
  observable rather than opaque (§22). Not one row per turn: the history table
  records what the *engine* ran, and inventing rows for steps it never scheduled
  would cost the table its meaning,
* the loop cannot alter the workflow graph, request an approval on its own or reach a capability the
  step did not list.

This is the narrow place where §37 bends: an agent step lets the model drive within a fence the
workflow built. It never lets the model own the control flow — the moment the step returns, the
declarative graph is back in charge.

---

# 10. Capability Architecture

A capability is a named, invocable unit of work.

```text
Capability
 │
 ├── Name
 ├── Description
 ├── Kind             (application | external)
 ├── Owner
 ├── Version
 ├── Permissions
 ├── Input Schema
 ├── Output Schema
 ├── Execution Policy
 └── Execution        (how it is invoked)
```

An externally provided capability:

```json
{
  "id": "venue_search",
  "description": "Find suitable venues",
  "kind": "external",
  "owner": "platform-team",
  "version": "1.0",
  "permissions": ["places.read"],

  "inputSchema": {
    "type": "object",
    "properties": {
      "location": {
        "type": "string"
      }
    }
  },

  "execution": {
    "type": "mcp",
    "server": "places",
    "tool": "search"
  }
}
```

A capability backed by the application's own business code:

```json
{
  "id": "calculate_discount",
  "description": "Apply tier-based discount rules",
  "kind": "application",
  "owner": "billing-team",
  "version": "2.1",
  "permissions": ["billing.price"],

  "execution": {
    "type": "grpc",
    "target": "billing-service"
  }
}
```

The workflow sees neither block. It sees `"capability": "calculate_discount"` and
`"capability": "venue_search"` — indistinguishable by design (§9.8). Everything that differs between
them — who owns it, how it is deployed, what version is pinned, what it is allowed to touch — is
registration metadata.

The abstraction allows:

```text
Capability
 ├── MCP
 ├── REST
 ├── GraphQL
 ├── Database
 ├── Java Function
 ├── Python Function
 └── Remote Service
```

MCP is therefore an integration mechanism, not the core abstraction.

---

# 11. Prompt Registry

Prompts are first-class artifacts.

```text
prompts/

create_event/
    extraction/
        v1.md
        v2.md

find_match/
    analysis/
        v1.md
```

Workflow references prompts by ID:

```json
{
  "prompt": "create_event.extraction.v2"
}
```

This enables:

* prompt versioning,
* rollback,
* A/B testing,
* independent deployment,
* reproducibility.

---

# 12. Model Registry

Models are also configuration-driven.

```json
{
  "models": {

    "fast": {
      "provider": "openai",
      "model": "gpt-5-mini",
      "protocol": "responses"
    },

    "reasoning": {
      "provider": "anthropic",
      "model": "claude",
      "protocol": "messages"
    },

    "local": {
      "provider": "vllm",
      "model": "qwen",
      "protocol": "openai-compatible"
    }
  }
}
```

A workflow references:

```json
{
  "model": "reasoning"
}
```

It never references provider-specific credentials.

---

# 13. Messaging Provider Abstraction

The application should communicate with the model through an internal interface.

```java
interface MessagingProvider {

    CompletionResponse complete(
        CompletionRequest request
    );

    CompletionResponse stream(
        CompletionRequest request,
        Consumer<CompletionChunk> onChunk
    );
}
```

Streaming takes a callback rather than returning a `Stream`. A lazily consumed
stream ties an open connection to whenever the caller gets round to draining it,
and makes a step's synchronous contract someone else's problem; a callback keeps
the connection's lifetime inside the provider. The default implementation
degrades to a single chunk, so a provider that cannot stream still works
everywhere streaming is asked for.

Providers:

```text
MessagingProvider
 ├── OpenAI
 ├── Anthropic
 ├── Gemini
 ├── OpenAI-Compatible
 └── Local
```

This prevents vendor lock-in.

---

# 14. Execution Context

Every workflow execution has a context.

```java
class ExecutionContext {

    ExecutionId executionId;

    WorkflowId workflowId;

    Intent intent;

    Input input;

    Map<String, Object> variables;

    String currentStep;

    ExecutionMetadata metadata;
}
```

Example:

```text
ExecutionContext
│
├── user
├── input
├── intent
├── event
├── venues
├── approval
├── createdEvent
└── metadata
```

Steps communicate through the context rather than directly depending on one another.

---

# 15. Execution State

Workflow execution must be persistent.

```text
RUNNING
   │
   ▼
WAITING_FOR_APPROVAL
   │
   ▼
RESUMED
   │
   ▼
RUNNING
   │
   ▼
COMPLETED
```

Possible states:

```text
CREATED
RUNNING
WAITING
PAUSED
COMPLETED
FAILED
CANCELLED
```

The runtime must persist enough state to resume execution after:

* process restart,
* deployment,
* network failure,
* approval delay,
* worker failure.

---

# 16. No Thread Blocking for Long Waits

Human approval must never hold an application thread.

Bad:

```java
approvalFuture.get();
```

Preferred:

```text
Workflow
    ↓
WAITING_FOR_APPROVAL
    ↓
persist state
    ↓
release resources
    ↓
<later>
    ↓
approval event
    ↓
resume workflow
```

This makes the engine suitable for long-running workflows.

---

# 17. Retry Policies

Retries belong to execution policy rather than business logic.

```json
{
  "retry": {
    "maxAttempts": 3,
    "backoff": "exponential",
    "initialDelay": "500ms",
    "maxDelay": "30s"
  }
}
```

Policies may exist at:

```text
Global
Workflow
Step
Provider
```

levels.

---

# 18. Failure Handling

Every step should support:

```text
success
retry
fallback
failure
timeout
```

Example:

```json
{
  "id": "generate_answer",

  "type": "llm",

  "onFailure": {
    "strategy": "fallback",
    "model": "local"
  }
}
```

The runtime, not the LLM, controls failure semantics.

---

# 19. Intent Resolution

Intent resolution is intentionally separated from workflow execution.

```text
User Input
    │
    ▼
Intent Resolver
    │
    ▼
Intent
    │
    ▼
Workflow Registry
    │
    ▼
Workflow
```

Example:

```json
{
  "intents": {

    "create_event": {
      "description": "User wants to create an event",
      "workflow": "create_event"
    },

    "find_match": {
      "description": "User wants to find a language exchange match",
      "workflow": "find_match"
    }
  }
}
```

The resolver itself can use an LLM, deterministic rules, embeddings, or a hybrid approach.

The workflow engine should not care.

---

# 20. Deterministic vs AI Decisions

This distinction is fundamental.

Use deterministic conditions for:

```text
price > 0
user.age >= 18
status == ACTIVE
payment.completed == true
```

Use an LLM for:

```text
What does the user mean?
Is this request ambiguous?
Which category does this request belong to?
Extract structured information from natural language.
```

This dramatically improves reliability.

---

# 21. Structured LLM Output

LLM steps should support schemas.

```json
{
  "type": "llm",

  "outputSchema": {
    "type": "object",
    "required": [
      "location",
      "date",
      "participantCount"
    ]
  }
}
```

The runtime validates the model response before writing it to the execution context.

```text
LLM
 ↓
Raw response
 ↓
Schema validation
 ↓
Valid?
 ├── yes → Context
 └── no  → Retry / Repair / Failure
```

---

# 22. Observability

Every workflow execution should generate a trace.

```text
Execution
│
├── IntentResolver
│   ├── latency
│   └── model
│
├── Step: extract_event
│   ├── latency
│   ├── tokens
│   ├── model
│   └── promptVersion
│
├── Step: venue_search
│   ├── latency
│   └── MCP tool
│
├── Step: approval
│   └── waitingTime
│
└── Step: create_event
    ├── latency
    └── result
```

The design should be compatible with OpenTelemetry.

Important metrics include:

```text
workflow.duration
workflow.success_rate
workflow.failure_rate

llm.latency
llm.input_tokens
llm.output_tokens
llm.cost

tool.latency
tool.failure_rate

approval.wait_time
```

---

## 22.1 One Boundary, Many Backends

Telemetry leaves the runtime through a single narrow interface:

```text
                    WorkflowExecutor
                           │
                    ExecutionObserver
                           │
              ┌────────────┼────────────┐
              ▼            ▼            ▼
         OpenTelemetry   Logging     (native)
              │
    ┌─────────┼─────────┬──────────┐
    ▼         ▼         ▼          ▼
 Datadog  New Relic  Grafana   Honeycomb
```

There is deliberately no adapter per vendor. Datadog, New Relic, Grafana and
Honeycomb all ingest OpenTelemetry, so an OTLP exporter reaches all of them and a
vendor module is only worth writing for someone who wants a native API. Several
observers can run at once — an organization migrating from one backend to another
should be able to send to both for a week.

Two properties matter more than the interface itself:

* **Every method has a default.** A later event — a retry, a branch join, a budget
  warning — must not break an implementation written today.
* **An observer cannot fail an execution.** Whatever it throws is contained before
  it reaches the engine. Telemetry going dark is a bad day; telemetry taking a
  workflow down is an outage.

## 22.2 Organization as a Dimension

Every execution carries the organization it belongs to, and every span and metric
is labelled with it:

```text
pipemesh.organization
pipemesh.workflow.id
pipemesh.execution.id
pipemesh.step.id
```

It is carried from the first write rather than added when multi-tenancy becomes
urgent. An owner decides which rows a query may return and which series a metric
lands in; retrofitting it means migrating every row and re-labelling every
dashboard. A single-tenant deployment never has to think about it — the value
defaults.

Labelling is not isolation, and the two arrived separately. A caller may not
read, resume or start work belonging to another organization, and the
organization comes from the resolved principal rather than from the request — a
caller naming another's would otherwise reach that organization's workers, since
worker routing follows the same dimension.

What still belongs to separate work: metering what each organization consumes,
quotas, and tenant-wide queries. And one honest limit — tenants cannot be kept
apart without telling callers apart, so a deployment that identifies nobody has
no isolation to enforce.

## 22.3 One Trace Across a Wait

A durable workflow breaks tracing in a way an ordinary request does not. An
execution that waits three days for an approval and then finishes in a different
process would naturally produce two unrelated traces — precisely at the moment
someone is trying to understand what happened.

So the trace context is persisted with the execution state and read back on
resume:

```text
start          →  trace generated, or inherited from the caller
suspend        →  traceparent written with the state
<process ends>
resume         →  traceparent read back, spans continue the same trace
```

A caller that is already inside a trace passes its `traceparent` in with the
request, so the workflow appears underneath what asked for it rather than as a
trace of its own.

This is why the state schema carries a trace column from the beginning. It is the
one part of observability that cannot be added later without rewriting history.


---

# 23. Security Model

Capabilities should have explicit permissions.

Example:

```json
{
  "id": "event_creation",

  "permissions": [
    "event:create"
  ]
}
```

A workflow should not automatically gain access to every registered capability.

Possible policy:

```text
Workflow
   ↓
Allowed Capabilities
   ↓
Allowed Tools
   ↓
Allowed Resources
```

This becomes especially important for MCP.

Permissions are declared on the capability registration, never on the workflow step (§9.8). The
runtime checks them at invocation time:

```text
capability invocation
        ↓
resolve registration
        ↓
permission check
        ↓
allowed?  ──no──▶  fail the step
        │
       yes
        ↓
execute
```

This is the reason a separate step type for application code would have bought nothing: the
enforcement point is the registry, not the DSL.

## 23.1 No Inline Code in Workflow Definitions

A workflow definition must never carry executable source code:

```json
{
  "type": "code",
  "code": "import os; ..."
}
```

This is rejected by design. Embedding code would drag sandboxing, dependency management,
deployment, versioning and debugging of a foreign language into the runtime — and would make every
workflow definition a remote code execution vector.

A workflow may only *name* a capability:

```json
{
  "type": "capability",
  "capability": "calculate_discount"
}
```

The code lives in the application or worker that registered it, deployed and versioned on its own
terms. The runtime resolves the name; it never interprets a body.

---

# 24. Versioning

Everything should be versionable.

```text
Workflow
Prompt
Capability
Model configuration
Schema
```

Example:

```text
create_event@1.2
event_extraction@2.1
venue_search@1.0
```

An execution should record exact versions:

```json
{
  "workflow": "create_event@1.2",
  "prompt": "event_extraction@2.1",
  "model": "reasoning@1",
  "capabilities": [
    "venue_search@1.0"
  ]
}
```

This enables reproducibility.

## 24.1 An execution finishes in the graph it started in

Recording a version is not the same as honouring one. A registry keyed by id alone would let a
deploy move a suspended execution to a different graph mid-flight — and with approvals and events
in the language (§9.7, §16), suspended for days is the ordinary case, not the exception. So the
registration key is the pair:

```text
register:  (id, version) → graph        a new version does not displace the old
start:     no version given → newest    chosen once, then written to the record
resume:    always the record's version  not a preference, a fact
```

Which version a suspended execution continues on cannot be a choice made at resume time, because
whoever is resuming — an approver, a payment service, a recovery sweep — does not know where the
execution stopped or what that step meant.

**"Newest" needs an order, so the version language is narrow:** dot-separated numbers, compared as
numbers. `"10.0"` is newer than `"9.0"`, which text ordering gets backwards. Anything that is not
a number is refused when it is written rather than when someone asks for the newest — by then the
wrong graph has already been chosen and nobody is watching.

**Ordering and identity are different questions.** `1.0` and `1.0.0` compare equal but are two
registrations. The registry keys on the exact string; only the choice of "newest" consults the
order. Conflating them would run a version other than the one recorded.

**A version is immutable.** Re-registering the same `(id, version)` with a different definition is
refused; with the same definition it is a no-op, because restarting a process and reading the same
directory must not be an error. Without this, "it ran on 1.2" says nothing and the reproducibility
above is a sentence rather than a property.

**Migrating a running execution to a new version is a separate problem** — it means deciding what
`currentStep` means in a graph that may not contain it, which is a mapping language, not a lookup.
The record already knows where it stopped and on what, so that work stays possible; it is simply
not this rule.

---

# 25. Workflow Compilation

A workflow should be validated and compiled before execution.

```text
JSON
 │
 ▼
Schema Validation
 │
 ▼
Semantic Validation
 │
 ▼
Workflow Compiler
 │
 ▼
Execution Graph
```

Semantic validation should detect:

* missing step IDs,
* invalid transitions,
* cycles where prohibited,
* missing capabilities,
* missing prompts,
* invalid schemas,
* unreachable nodes,
* undefined variables.

This moves errors from runtime to deployment/startup time.

---

# 26. Runtime Architecture

```text
                   WorkflowRuntime
                         │
          ┌───────────────────┼──────────────────┐
          │                   │                  │
          ▼                   ▼                  ▼
     FlowRegistry     CapabilityRegistry    ModelRegistry
          │                   │                  │
          └───────────────────┼──────────────────┘
                         ▼
                  WorkflowExecutor
                         │
                  ┌──────┴───────┐
                  ▼              ▼
             Scheduler      StateStore
                  │              │
                  ▼              ▼
             StepExecutor    Persistence
                  │
       ┌──────────┼───────────┐
       ▼          ▼           ▼
      LLM     Capability   Approval
```

---

## 26.1 Client Boundary

The runtime is written in Java, but nothing about a workflow is. Callers reach the runtime through
a language-neutral gRPC boundary, and SDKs are generated from a single `.proto` contract.

```text
                 Developer
                     │
        ┌────────────┼────────────┐
        ↓            ↓            ↓
     Python         Java       TypeScript
      SDK            SDK          SDK
        │            │            │
        └────────────┼────────────┘
                     │
                   gRPC
                     │
                     ▼
             ┌───────────────┐
             │   PipeMesh    │
             │    Runtime    │
             │     Java      │
             └───────┬───────┘
                     │
          ┌──────────┼──────────┐
          ↓          ↓          ↓
       Workflow    State    Providers
          │
      ┌───┴────┐
      ↓        ↓
     LLM      MCP
```

### The proto is the contract

The `.proto` file — not the Java interface — is the authoritative API definition. The Java API is
one binding among several; the gRPC service is a thin adapter over the same core, never a second
implementation with its own semantics.

```text
pipemesh.proto
      │
      ├── generated: Python SDK
      ├── generated: TypeScript SDK
      └── generated: Java SDK

Core Java API  ──adapter──▶  gRPC service
```

Consequences the API must respect from day one:

* identifiers are strings, never Java types,
* execution variables cross the boundary as JSON (`google.protobuf.Struct`),
* every request and response must be serializable — no in-memory handles,
* the proto is a versioned artifact like workflows, prompts and capabilities (§24).

### Java has two paths

A Java caller running in the same process should not pay for a network hop:

```text
Java application ──┬── embedded: import the runtime library (in-process)
                   └── remote:   gRPC SDK (same contract, separate process)
```

Both paths expose the same operations. The embedded path is the one the runtime itself uses.

### Two directions of traffic

The boundary carries traffic both ways, and the two directions are not symmetric.

**Outbound — the caller drives the runtime** (unary + server streaming):

```text
StartExecution      unary            begin a workflow
SubmitApproval      unary            resume a suspended execution
GetExecution        unary            current state snapshot
WatchExecution      server stream    execution events and token stream (§22, §30)
```

**Inbound — the runtime invokes a capability implemented in an SDK language:**

```text
Runtime ──▶ needs capability "venue_search" implemented in Python
```

This cannot be a plain request from the SDK. The chosen mechanism is a long-lived bidirectional
stream opened by the worker:

```text
SDK worker ──open bidi stream──▶ Runtime
                                    │
           ◀──── CapabilityInvocation ───┤   runtime pushes work
           ────── CapabilityResult ─────▶│
```

A worker-initiated stream keeps the SDK reachable without an inbound address, TLS certificate or
firewall exception on the worker side. The alternative — the runtime dialing a gRPC server hosted
by the SDK — is simpler but requires every capability worker to be individually addressable.

An SDK-hosted capability is one `CapabilityProvider` implementation among others (§10). It does not replace
MCP: MCP remains the mechanism for external tools, while SDK capabilities exist for in-language business
logic that should not be exposed as a tool at all.

### Not in the initial milestone

The boundary is designed early because it constrains the core API shape, but it is not implemented
in Phase 1 (§45). The first milestone proves the execution model in-process; the proto is written
alongside it so the API cannot drift into something unserializable.

---

## 26.2 Runtime, SDK and Provider

Three concepts are easy to conflate and must stay separate:

```text
Runtime   →  the engine: workflow execution, state, scheduling
SDK       →  how a developer reaches the runtime
Provider  →  how the runtime reaches the outside world
```

```text
┌──────────────────────────────┐
│      User's application      │
│  business logic              │
│          │                   │
│          ▼                   │
│     PipeMesh SDK             │
└──────────────┬───────────────┘
               │ gRPC
               ▼
┌──────────────────────────────┐
│       PipeMesh Runtime       │
│  workflow · state · scheduler│
└──────────────┬───────────────┘
               │
       ┌───────┼────────┐
       ▼       ▼        ▼
      LLM     MCP     Tasks
```

The distinction resolves several questions at once. LangChain, OpenAI and MCP are all **providers** —
optional adapters, never dependencies of the core (§35). A Python or TypeScript package is an
**SDK** — an access protocol, not a second execution engine. There is exactly one runtime.

## 26.3 Deployment Modes

**Embedded** — a Java application runs the runtime inside its own JVM:

```java
PipeMesh mesh = PipeMesh.builder()
    .config("./pipemesh")
    .build();
```

**Remote** — any language reaches a runtime process over gRPC:

```bash
docker run pipemesh/runtime
```

```python
mesh = PipeMesh("localhost:8080")
result = mesh.execute("create_event", {"message": "..."})
```

**Shared** — several applications, in different languages, use one runtime deployment:

```text
Python App A ─┐
Python App B ─┼──▶  PipeMesh cluster
Java App C ───┘
```

At that point the runtime is a platform rather than a library, and the state store becomes the
system of record for every workflow in the organization.

### The SDK must not launch the runtime

An SDK must never spawn the runtime as a subprocess:

```python
subprocess.Popen(["java", "-jar", "pipemesh.jar"])   # not the architecture
```

It looks convenient and quietly makes every SDK responsible for process lifecycle, crash handling,
logging, networking, versioning and containerization. An SDK's job is to talk to a runtime, not to
operate one.


---

## 26.4 SDK Surface

An SDK exposes three verbs. They differ in how the workflow is chosen and in how the result comes
back — not in what the runtime does underneath.

```text
                         ┌─────────────────────┐
                         │     PipeMesh SDK    │
                         └──────────┬──────────┘
                                    │
                    ┌───────────────┼───────────────┐
                    │               │               │
                    ▼               ▼               ▼
               execute()       process()       stream()
                    │               │               │
              Explicit flow    Intent → flow    Long-running
                    │               │               │
                    └───────────────┼───────────────┘
                                    ▼
                         ┌─────────────────────┐
                         │   PipeMesh Runtime  │
                         │        Java         │
                         ├─────────────────────┤
                         │ Execution Engine    │
                         │ Workflow Engine     │
                         │ State               │
                         │ Streaming           │
                         │ Intent Resolution   │
                         │ Agent Loop          │
                         │ Human Approval      │
                         └──────────┬──────────┘
                                    │
                    ┌───────────────┴───────────────┐
                    ▼                               ▼
                   LLM                          Capability
                    │                               │
        ┌───────┬───┴───┬───────┐      ┌──────┬─────┼──────┬──────────┐
        ▼       ▼       ▼       ▼      ▼      ▼     ▼      ▼          ▼
     OpenAI Anthropic Bedrock Local   MCP   REST  gRPC  Function   Worker
                                       │
                                       ▼
                                     Tools
```

| Verb | The caller supplies | The runtime does | Comes back as |
|---|---|---|---|
| `execute()` | a workflow id and input | runs the named workflow | the final result |
| `process()` | natural language | resolves intent, then runs the workflow it selected (§19) | the final result |
| `stream()` | either of the above | the same execution, observed as it happens | a stream of events and tokens (§30) |

`process()` is `execute()` with one step in front of it. Intent resolution picks a workflow; it does
not run one. Keeping it a separate verb is what stops "the model chose the flow" from becoming "the
model runs the flow" (§20, §37).

`stream()` is not a third execution model. It is `WatchExecution` attached to the same run, which is
why an execution started with `execute()` can be observed later without having been started
differently.

### Blocking belongs to the caller, never to the runtime

`execute()` returning a final result implies waiting — but the waiting happens in the caller's
thread, on the caller's side of the boundary. Inside the runtime nothing is held: an execution that
reaches an approval is persisted and its resources released (§16). A workflow that suspends for three
days does not keep an `execute()` call open for three days; it returns with the execution in
`WAITING`, and the caller resumes or observes it later.

A convenience that blocks a client thread is a client concern. A runtime that blocks its own threads
is a broken runtime.


---

# 27. Step Executor

The core executor should be extensible.

```java
interface StepExecutor {

    boolean supports(StepType type);

    StepResult execute(
        Step step,
        ExecutionContext context
    );
}
```

Implementations:

```text
LlmStepExecutor
CapabilityStepExecutor
ConditionStepExecutor
ApprovalStepExecutor
ParallelStepExecutor
TransformStepExecutor
WaitStepExecutor
```

Adding a new primitive should not require modifying the central engine.

---

# 28. Event-Driven Execution

The engine should avoid a purely synchronous architecture.

```text
Workflow Event
      │
      ▼
Scheduler
      │
      ▼
Runnable Step
      │
      ▼
Result Event
      │
      ▼
Next Step
```

This allows horizontal scaling.

Multiple workflow executions can be distributed across workers:

```text
                 Queue
                   │
       ┌───────────┼───────────┐
       ▼           ▼           ▼
    Worker 1    Worker 2    Worker 3
```

## 28.1 Work is taken, not assigned

There is no assigner in the middle. Each runtime instance asks the store for executions nobody is
driving and takes a lease on what it gets:

```text
claim(owner, duration, limit)  →  up to `limit` executions, now mine
renew(lease, duration)         →  still here
release(lease)                 →  done, or giving up
```

An assigner would be both a single point of failure and a second answer to "what is runnable" when
the rows already know. Claiming keeps the authority in one place: the row itself.

**The queue is the database.** A broker in `core/` would break the framework-free rule and add that
second answer. `claim` is one statement — a CTE selecting drivable executions `FOR UPDATE ... SKIP
LOCKED`, then upserting the lease. `SKIP LOCKED` is what makes a second instance add throughput
instead of latency: two of them stepping over each other's locked rows rather than queueing behind
them. This has a real ceiling — it is polling, not push — and `ExecutionLeases` is where a broker
plugs in when the ceiling is reached.

**A lease is not execution state.** Who is currently driving is an operational fact about the
deployment. It lives in its own table, never in `ExecutionRecord`, and so never reaches a snapshot,
a telemetry event or the wire, where it would mean nothing to a caller.

**A lease does not prove the owner is alive.** It proves the owner is renewing. A heartbeat says the
process is up, not that the work is moving — a driver wedged inside one step goes on renewing while
advancing nothing. This is why §15's recovery sweep stays: it watches when the execution row was
last *written*, which a wedged step does not update. The two mechanisms answer different questions,
and neither replaces the two safety nets underneath both — the version check means only one writer
can advance an execution, and `repeatable` means a step that may already have had an effect is never
run twice.

**Only what can move on its own is claimable.** An execution waiting for a person or an event is not
stuck; claiming it would occupy a driver with work that cannot progress.

**Distributing is opt-in.** `StartMode.INLINE` — the calling thread drives the execution — stays the
default, because nobody running a single instance should have to stand up a dispatcher to see a
workflow run. `StartMode.DISPATCHED` writes the execution down and returns, leaving it for whichever
instance claims it. The trap is worth stating: a dispatched runtime with nothing dispatching accepts
work and never runs it.

---

# 29. Concurrency Model

Parallel branches should be explicit.

```text
                START
                  │
             ┌────┴────┐
             ▼         ▼
          Search     Profile
             │         │
             ▼         ▼
          Result     Result
             └────┬────┘
                  ▼
                 JOIN
```

The runtime should track branch completion and only continue when the join condition is satisfied.

Branches run inside the parallel step rather than as positions of their own. An
execution has one row, one version and one writer; two branches advancing it
would spend their lives losing to each other's version check. What is expensive
is the I/O, and that is what runs concurrently — persistence stays where the
durability model needs it, at one step and one write.

The cost is real and worth stating: steps inside a branch get no history rows and
no durability of their own, so a process that dies mid-parallel makes recovery
re-run the whole step. A parallel step therefore asks every branch whether it may
be repeated before recovery is allowed to try (§15).

---

# 30. Streaming

Streaming should be supported at the provider boundary.

```text
LLM Provider
    ↓
Token Stream
    ↓
Workflow Runtime
    ↓
Application Stream
```

The runtime should not force applications into request/response semantics.

Tokens reach a caller through the same channel as execution events. The wire
protocol already merges them into one stream (§26.4), so a single in-process
channel means the gRPC adapter is one more observer rather than a second fan-out
mechanism. An observer that has no interest in tokens ignores them by default.

Streaming changes how an answer arrives, never what it is: the step still ends
with a complete response, still validates it against its schema (§21), and still
writes one variable. A workflow reads the same either way.

## 30.1 What a watcher may see, and who may watch

Streaming is not the answer to long-running work — durable execution is (§15). Its job is
answering "what is happening right now", and that question was badly served while a forty-second
step produced nothing until it ended.

**A step announces itself before it runs.** `step.started` carries the attempt number, so three
retries read as three attempts rather than one slow step.

**Nothing below a step is announced.** `llm.started`, `capability.started` and their kind are
deliberately absent, and the rule behind that is worth stating plainly:

> An event may only claim something durable state can corroborate.

A step start is corroborated: the stored record already says the execution stands at that step,
so a watcher's view survives the process that produced it. Work *inside* a step has no such
witness — publish `capability.started`, lose the process, replay the step, and the client is the
only one who ever believed it happened. Tokens are the deliberate exception, because a token is
not a claim about state; it is the output itself.

**Recovery is announced at both endings.** An execution picked up after a crash either continues
or stops for a person because its step could not be repeated (§15). Both are recoveries and both
are published, told apart by `repeated` — the second is the one worth hearing about, and it used
to be the silent one.

**A watcher may decline the noisy parts.** Tokens and progress can each be turned off per
subscription. Status updates cannot: a stream that could omit `finished` would leave a caller
waiting for something already over. Sequence numbers are assigned before filtering, so a filtered
watcher sees gaps — and a gap says "not for you", which is what keeps filtering distinguishable
from loss.

## 30.2 Updates across processes

A broker holding watchers in memory is correct while there is one process and a silent failure
the moment there are two: a watch lands on the replica the client happened to reach, the
execution runs on the one that claimed it (§28.1), and the stream says nothing at all — which
§30.1 already establishes is indistinguishable from a workflow that is thinking.

Updates therefore travel over PostgreSQL `LISTEN/NOTIFY`. The database is already shared, so
this needs nothing new to operate; Redis, NATS or Kafka would each be a third thing to run for a
job Postgres does adequately at this size, and `UpdateChannel` is where one of them plugs in when
that stops being true.

**Local delivery stays.** A process serves its own watchers directly and publishes alongside,
rather than routing everything through the database. Two reasons: a runtime without a database
still has to work, and a single node should not pay a round trip to talk to itself. A publisher
ignores its own notifications, or every stream would double.

**A sequence number describes a stream, not an execution.** Whichever process serves a watcher
numbers what it sends it. Two processes cannot share a counter without sharing a lock, and a
client only ever reads one of them — so the numbers are monotonic within a stream, gaps still
mean "filtered, not lost" (§30.1), and `from_sequence` stays unimplemented rather than
half-true.

**Two properties are inherited from `NOTIFY` and are not regressions.** It is not durable, so a
process that is not listening misses what happened — exactly what the in-memory broker has always
done. And its payload is limited, so an update too large to carry is replaced by a marker saying
something happened, rather than dropped: the watcher loses the detail and does not lose the
event.

**Whether an organization may watch at all is a deployment's decision**, expressed as a
permission the caller either holds or does not (§23). No new mechanism: the same permission set
`CapabilityInvoker` already reads, with a second reader. It is off by default, because demanding
a permission of callers nobody authenticated would deny every deployment that never set up a
resolver — the same reasoning as §22.2, where a deployment that identifies nobody has no
isolation to enforce either. And when it is on, a refusal is an error rather than an empty
stream: a stream that opens and closes looks exactly like nothing happening.

---

# 31. Configuration Repository

Recommended structure:

```text
runtime/
│
├── workflows/
│   ├── create-event.json
│   ├── find-match.json
│   └── cancel-event.json
│
├── intents/
│   └── intents.json
│
├── models/
│   └── models.json
│
├── capabilities/
│   ├── venue-search.json
│   └── event-creation.json
│
├── prompts/
│   ├── create-event/
│   │   ├── extraction.v1.md
│   │   └── extraction.v2.md
│   └── find-match/
│       └── analysis.v1.md
│
└── schemas/
    └── workflow.schema.json
```

---

# 32. Separation of Concerns

The architecture should enforce the following boundaries:

```text
Workflow
  knows:
    steps
    transitions
    policies

Capability
  knows:
    what it can do
    input/output schema
    provider

Provider
  knows:
    external protocol
    authentication
    transport

Model
  knows:
    model configuration

Prompt
  knows:
    instructions

Runtime
  knows:
    execution
    state
    scheduling
    retries
```

No layer should leak unnecessary implementation details into another layer.

---

# 33. Example End-to-End Execution

User:

```text
"I want to organize an English meetup in Antalya
for 6 people tomorrow."
```

Execution:

```text
INPUT
 │
 ▼
Intent Resolver
 │
 ▼
create_event
 │
 ▼
Workflow
 │
 ▼
extract_event
 │
 ▼
LLM
 │
 ▼
{
  location: "Antalya",
  participants: 6,
  date: "..."
}
 │
 ▼
validate
 │
 ▼
venue_search
 │
 ▼
MCP
 │
 ▼
venues
 │
 ▼
recommendation
 │
 ▼
human_approval
 │
 └──────── WAITING
              │
              │ approval event
              ▼
          create_event
              │
              ▼
            DONE
```

The entire execution can be represented as a traceable state transition history.

---

# 34. Why JSON?

JSON is not selected because it is the only possible format.

It is selected because it provides:

* broad tooling support,
* schema validation,
* IDE support,
* language independence,
* easy serialization,
* easy storage,
* easy version control,
* human readability,
* compatibility with existing workflow ecosystems.

The internal representation should remain language-native and strongly typed.

```text
JSON
 ↓
Typed Definition
 ↓
Compiled Graph
 ↓
Runtime
```

JSON is therefore the **configuration/DSL boundary**, not the runtime representation.

---

# 35. Why Not Put Everything Inside LangChain?

LangChain Core provides valuable primitives for:

* model abstraction,
* runnable composition,
* messages,
* tools,
* structured output,
* streaming.

However, application-specific workflow semantics should remain independent.

Recommended architecture:

```text
                 Application
                      │
                Workflow Runtime
                      │
          ┌───────────┴────────────┐
          │                        │
    LangChain Core               MCP
          │                        │
          ▼                        ▼
       Models                    Tools
```

LangChain becomes an implementation dependency rather than the architecture itself.

This prevents framework lock-in.

**Decision:** PipeMesh is not built on LangChain. LangChain is supported as an optional adapter —
one provider among others — shipped separately (`pipemesh-langchain`) so that the core never
depends on it:

```text
                  PipeMesh Runtime
                         │
        ┌────────────────┼────────────────┐
        ↓                ↓                ↓
   OpenAI adapter    MCP adapter    LangChain adapter
```

The ecosystem is worth reaching; the dependency is not worth taking.

## 35.1 Where the adapter actually lives, and why it is so small

The diagram above puts `pipemesh-langchain` beside the OpenAI and MCP adapters, which are Java
modules. LangChain is a Python library. The Java-side thing with a similar name is LangChain4j — a
different project and a separate decision — so the LangChain adapter lives in the Python SDK. That
is not a compromise but a consequence of §26.2: a provider is where the runtime reaches outward,
and the way to reach something in Python is the worker connection the SDK already opens (§26.1).

**It needed no new protocol.** A LangChain chain is already reachable as a capability. The adapter
is translation and nothing else — connection, retry, registration and shutdown all come from the
worker unchanged:

```python
worker = PipeMeshWorker("localhost:8080", organization="acme")
serve(worker, "summarize", summarize_chain, field="article")
worker.run()
```

```json
{"type": "capability", "capability": "summarize", "input": "$.article", "output": "summary"}
```

That an adapter for a whole ecosystem is this small is the evidence that the abstraction sits in
the right place. The alternative — a new RPC per ecosystem — would mean the abstraction was never
the capability, only a special case per vendor.

**The adapter does not import LangChain either.** It accepts anything with an `invoke()` method,
which `Runnable` and `BaseTool` both have. If the sentence above is worth saying about the core, it
is worth honouring in the adapter. The cost is real and stated: a change to LangChain's `invoke`
contract surfaces at run time rather than at import time — so the shape is checked when a runnable
is registered, not on the first call.

**A LangChain model cannot back an `llm` step**, and that limit is structural rather than
temporary: the proto has an inbound path for capabilities and none for models. Whether it should is
a separate question — a model one hop away puts cost accounting (§39.1) and token streaming (§30)
behind that hop. A LangChain *chain* is application logic, and behind a capability is where
application logic belongs.

---

# 36. Comparison With Existing Systems

The design is conceptually related to several established systems:

```text
AWS Step Functions
    → declarative workflow execution

Netflix/Orkes Conductor
    → JSON-defined distributed workflows

Temporal
    → durable workflow execution

Semantic Kernel
    → AI-oriented process orchestration

LangChain
    → LLM/tool composition

MCP
    → standardized tool/resource integration

AWS Bedrock AgentCore
    → managed infrastructure for production agents
```

The closest overlap is with managed agent platforms. The separation is one of layer, not of feature
list:

```text
              AI APPLICATIONS
                     │
      ┌──────────────┴──────────────┐
      │                             │
 AI PLATFORM                  AI WORKFLOW
      │                             │
 runtime hosting              declarative DSL
 gateway · identity           execution engine
 memory · tools               state · approval
      │                             │
 tied to one cloud            provider agnostic
```

A managed platform answers *"where does my agent run?"*. PipeMesh answers *"how does this workflow
proceed?"*. They compose rather than compete: such a platform can host the runtime, its gateway can
appear as an MCP-backed capability, and its models can be registered as a model provider — none of which
requires the workflow definition to change.

The proposed architecture combines these ideas around a single principle:

> **AI workflows should be declarative, versioned, observable, resumable, and provider-independent.**

---

# 37. Critical Design Decision: LLM Is Not the Orchestrator

The runtime should remain the authority over execution.

Instead of:

```text
LLM → decide everything → execute
```

use:

```text
Runtime
 │
 ├── deterministic workflow
 │
 ├── LLM where reasoning is required
 │
 ├── tools where external state is required
 │
 └── human approval where authorization is required
```

This makes the system substantially more predictable.

---

# 38. Reliability Model

The runtime should distinguish between:

### Deterministic failure

Examples:

```text
MCP timeout
HTTP 500
Database failure
Invalid schema
```

These can be handled programmatically.

### Probabilistic failure

Examples:

```text
LLM misunderstood request
Incorrect classification
Invalid extraction
Hallucination
```

These require:

```text
schema validation
retry
repair
fallback
confidence checks
human approval
```

This distinction should be explicit in the architecture.

---

# 39. Future Extensions

The initial architecture should leave room for:

### Workflow version migration

```text
v1 → v2
```

### A/B testing

```text
workflow@1
    ├── variant A
    └── variant B
```

### Model routing

```text
simple task → cheap model
complex task → reasoning model
private task → local model
```

### Cost policies

```text
maxTokens
maxCost
maxModelCalls
```

## 39.1 Money is the missing budget

This codebase keeps making the same decision: a step budget, an agent turn limit, a worker
deadline, a wait timeout. Anything unbounded eventually becomes something nobody noticed. Money
was the member missing from that list — an agent that loops stops at its turn limit, while a
workflow sending long context ten times over had nothing stopping it.

```json
{ "id": "research", "version": "1.0", "entry": "plan",
  "budget": { "maxCost": "2.50", "maxModelCalls": 20, "maxTokens": 200000 },
  "steps": [ ... ] }
```

**A price is registration metadata.** A model registration says what it charges; the workflow
never mentions money, exactly as it never mentions how a capability is reached (§9.8). Prices
change with the registration and no workflow is touched.

**An unpriced model is not a free one.** A local model genuinely costs nothing and a forgotten
price looks identical from inside the runtime, so the two are kept apart: unpriced calls are
counted as unpriced, and a workflow that sets a money budget is *refused at compile time* a model
whose price nobody registered. A budget that cannot see what a step costs would let through
exactly the runs it exists to stop.

**Money is integer arithmetic** — micros, rounded once at the end. Per-token prices are far below
a cent, and what is being added up here is exactly money.

**Spend is execution state**, unlike a lease (§28.1): the decision it feeds is the execution's
own, it has to survive a restart, and "what did this run cost" is a question about the execution.
It is written in the same transaction as the step that caused it, for the reason every other write
here is (§15) — split them and a crash loses the money or counts it twice.

**The check happens before the next step, never inside one.** A provider call already made is
already paid for; abandoning it mid-flight wastes the money rather than saving it. So an execution
stops just after the step that took it over budget, not just before.

**Landing exactly on a limit is not overrunning it** — the same distinction the step budget
already makes, and the same bug if it is missed.

### What is deliberately not here

**Evaluation** — scoring how good an answer was — is not a runtime decision. What "good" means is
the application's knowledge, and §3 is exactly about the runtime knowing *when* something runs and
never *what* it does. Its likely shape is a capability, not a new engine concept.

**Model routing** ("simple task → cheap model") builds on the prices above, but where the routing
decision is made — workflow, registration, or a model of its own — is a design question of its own.

### Evaluation

```text
Workflow
 ↓
Execution
 ↓
Evaluation
 ↓
Quality Score
```

## 39.2 Routing and evaluation, and what each of them actually needs

### Evaluation needs no runtime feature

What counts as a good answer is the application's knowledge. A runtime that holds a quality
threshold has started doing the application's job, which is the same line §3 draws everywhere else:
the runtime knows *when* something runs and never *what* it does.

It is already expressible, and the shape is the obvious one — the scorer is a capability, the
threshold is a condition:

```json
{"id": "score", "type": "capability", "capability": "grade_answer",
 "input": "$.answer", "output": "quality", "next": "gate"},
{"id": "gate", "type": "condition", "expression": "$.quality.score < 0.6",
 "onTrue": "ask_human", "onFalse": "deliver"}
```

`EvaluationTest` runs exactly this, so the claim stays checkable rather than becoming a paragraph
nobody re-reads. A new step type here would buy nothing and would cost the boundary.

### Routing needed one thing: a model chosen at run time

Most of what §39's routing table asks for already worked. An alias points at whichever provider the
registration says (§12), so a deployment reroutes by editing `models.json`. A step can fall back to
a different model on failure (§18). A condition can branch to steps that use different aliases.

What was missing is reading the alias from a variable, so an application that has already
classified the work does not have to fan the workflow out into one branch per model:

```json
{"id": "answer", "type": "llm", "model": "$.route.model",
 "models": ["cheap", "reasoning", "local"], "prompt": "ask", "output": "a", "next": "done"}
```

**The decision stays in the application** — it wrote the variable. The runtime carries out what was
decided and never answers "is this task simple", because that question is precisely what §3 keeps
out of the engine.

**A run-time choice must declare what it is choosing from.** `models` is required whenever `model`
is a path, and an alias outside the declared set is refused. This is the agent step's rule (§9.9)
applied to models: whatever chooses may only choose from what the workflow declared. It also makes
the compile-time price check of §39.1 possible at all — an unbounded expression would make "which
models could this step call" unanswerable, while a declared set makes it a list.

**A route that does not resolve fails the step.** Falling back to a default nobody wrote down would
make which model ran invisible, which is the opposite of why the field exists.

### Human-in-the-loop review

```text
LLM
 ↓
Risk assessment
 ↓
Human review
 ↓
Continue
```

---

# 40. Proposed Project Structure

```text
pipemesh/
│
├── core/                    # Java — no framework dependency
│   ├── workflow/
│   ├── execution/
│   ├── state/
│   ├── scheduler/
│   └── expressions/
│
├── providers/
│   ├── messaging/
│   ├── models/
│   └── tools/
│
├── integrations/
│   ├── mcp/
│   ├── http/
│   └── messaging/
│
├── registry/
│   ├── workflow/
│   ├── capability/
│   ├── prompt/
│   └── model/
│
├── observability/
│   ├── tracing/
│   ├── metrics/
│   └── logging/
│
├── schemas/
│   ├── workflow.schema.json
│   ├── capability.schema.json
│   └── model.schema.json
│
├── spring/                  # Java — optional Spring Boot starter
│
├── sdk/
│   └── python/              # Python — client SDK and capability authoring
│
└── examples/
    ├── simple-chat/
    ├── tool-calling/
    ├── approval-flow/
    └── parallel-flow/
```

The core is Java; the workflow, capability and model artifacts are language-neutral JSON. A capability may be
implemented in any language behind the capability provider boundary, and any language may drive the
runtime through its remote boundary. Java is an implementation detail of the engine, never of a
workflow.

---

# 41. Design Principles

The project should follow these principles:

### 1. Declarative over imperative

Describe what should happen, not how the runtime implements it.

### 2. Model agnostic

The workflow should not depend on a specific LLM provider.

### 3. Tool agnostic

MCP is a provider, not the definition of a capability.

### 4. Deterministic where possible

Use code for deterministic logic.

### 5. AI where necessary

Use LLMs for reasoning and natural-language interpretation.

### 6. Explicit state

Every execution should have observable state.

### 7. Durable by design

Long-running workflows must survive process failures.

### 8. Version everything

Workflow, prompt, capability, schema and model configuration should be versioned.

### 9. Observable by default

Every model and tool invocation should be traceable.

### 10. Extensible without modifying the engine

New step types and providers should be plugins.

---

# 42. The Core Abstraction

The entire system can ultimately be reduced to five primitives:

```text
Workflow
Step
Capability
Provider
Execution
```

Everything else builds on top of these.

```text
Workflow
   │
   ├── Step
   │    ├── LLM
   │    ├── Capability
   │    ├── Condition
   │    ├── Approval
   │    ├── Parallel
   │    └── Wait
   │
   ├── Prompt
   ├── Policy
   └── Version

Capability
   │
   └── Provider

Execution
   │
   ├── Context
   ├── State
   ├── Events
   └── Trace
```

---

# 43. Final Architecture

The target architecture is:

```text
                         ┌───────────────┐
                         │    Client     │
                         └───────┬───────┘
                                 │
                                 ▼
                     ┌─────────────────────┐
                     │   Intent Resolver   │
                     └──────────┬──────────┘
                                │
                                ▼
                     ┌─────────────────────┐
                     │   Workflow Registry │
                     └──────────┬──────────┘
                                │
                                ▼
              ┌──────────────────────────────────┐
              │          Workflow Runtime        │
              │                                  │
              │   Compiler                       │
              │      ↓                           │
              │   Execution Graph               │
              │      ↓                           │
              │   Scheduler                      │
              │      ↓                           │
              │   Step Executors                 │
              └───────┬───────────┬──────────────┘
                      │           │
          ┌───────────┘           └────────────┐
          ▼                                    ▼
 ┌─────────────────┐              ┌──────────────────────┐
 │  Model Registry │              │  Capability Registry │
 └────────┬────────┘              └──────────┬───────────┘
          │                                    │
          ▼                                    ▼
 ┌─────────────────┐            ┌────────────────────────┐
 │ Messaging       │            │ MCP / REST / Services  │
 │ Providers       │            │ Database / Functions   │
 └─────────────────┘            └────────────────────────┘

                      │
                      ▼
             ┌─────────────────┐
             │ Execution State │
             │   + Event Log   │
             └────────┬────────┘
                      │
                      ▼
             ┌─────────────────┐
             │  Observability  │
             │ OpenTelemetry   │
             └─────────────────┘
```

---

# 44. Architectural Thesis

The central idea of the project is simple:

> **LLM applications should be built as durable, declarative workflows rather than collections of ad-hoc model calls.**

Models will change.

Providers will change.

MCP servers will change.

Prompts will change.

Business requirements will change.

The workflow runtime should remain stable.

```text
Models        → replaceable
Providers     → replaceable
Capabilities        → replaceable
Prompts       → versioned
Workflows     → declarative
Runtime       → stable
Execution     → observable + durable
```

The resulting system is not merely an LLM wrapper.

It is a **general-purpose runtime for executing AI-native workflows**.

---

# 45. Recommended Initial Milestone

The first implementation should intentionally be small.

### Phase 1

Implement only:

```text
Workflow JSON
Intent
LLM Step
Capability Step
Condition
Execution Context
Model Registry
Capability Registry
Messaging Provider
```

### Phase 2

Add:

```text
MCP
Structured Output
Retry
Timeout
Streaming
Prompt Registry
```

### Phase 3

Add:

```text
Persistent State
Human Approval
Resume
Parallel Execution
Event-driven execution
```

### Phase 4

Add:

```text
OpenTelemetry
Workflow versioning
Evaluation
Cost tracking
Model routing
Distributed workers
```

The goal is to avoid building a huge framework before proving the execution model.

---

# 46. Success Criteria

The architecture should eventually allow an engineer to add a new AI workflow by creating configuration rather than modifying runtime code.

For example:

```text
new-workflow.json
new-prompt.md
new-capability.json
```

without changing:

```text
WorkflowExecutor
Scheduler
ModelProvider
MCP integration
State management
Observability
```

That is the key architectural test.

If adding a new workflow requires modifying the engine, the abstraction is leaking.

If adding a new model requires modifying workflows, the provider abstraction is leaking.

If adding a new MCP tool requires modifying the executor, the capability abstraction is leaking.

A successful implementation keeps these boundaries stable.

---

# 47. One-Sentence Definition

> **A model-agnostic, provider-independent, declarative workflow runtime for building durable AI applications from versioned workflows, prompts, capabilities, tools, and human decisions.**

This is the architectural foundation.

The implementation should optimize for one property above all others:

> **Adding new AI behavior should require configuration and composition, not rewriting the runtime.**

