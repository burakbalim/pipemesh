# PipeMesh

## Technical Architecture & Design

**A declarative, model-agnostic runtime for building composable AI workflows.**

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

The runtime interprets this definition and executes it using pluggable providers and skills.

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
        ┌───────────────┼────────────────┐
        ▼               ▼                ▼
      Model           Skill           Approval
     Provider        Registry          Gateway
        │               │                │
        ▼               ▼                ▼
       LLM          MCP / API /      Human / System
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
5. Skill/tool abstraction.
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
* require MCP for every external operation.

The architecture explicitly favors deterministic code for deterministic operations.

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
Skill
 ↓
Approval
 ↓
Skill
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
│              ┌───────────────────────┼───────────────────┐ │
│              ▼                       ▼                   ▼ │
│        Model Registry         Skill Registry       Prompt Registry
│              │                       │                   │ │
└──────────────┼───────────────────────┼───────────────────┼─┘
               │                       │                   │
               ▼                       ▼                   ▼
        OpenAI / Claude         MCP / REST / DB        Prompt Files
        Local / vLLM            Internal Services
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
* skills,
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
      "type": "skill",
      "skill": "venue_search",
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
      "type": "skill",
      "skill": "event_creation",
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

## 9.2 Skill

Invokes an abstract capability.

```json
{
  "type": "skill",
  "skill": "venue_search"
}
```

The workflow should never need to know whether the skill is implemented using MCP, REST, Java code, or another mechanism.

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

Allows event-driven workflows.

```json
{
  "type": "wait",
  "event": "payment_completed"
}
```

---

# 10. Skill Architecture

A skill represents a capability.

```text
Skill
 │
 ├── Name
 ├── Description
 ├── Input Schema
 ├── Output Schema
 ├── Execution Policy
 └── Provider
```

Example:

```json
{
  "id": "venue_search",
  "description": "Find suitable venues",

  "inputSchema": {
    "type": "object",
    "properties": {
      "location": {
        "type": "string"
      }
    }
  },

  "provider": {
    "type": "mcp",
    "server": "places",
    "tool": "search"
  }
}
```

The abstraction allows:

```text
Skill
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

    Stream<CompletionChunk> stream(
        CompletionRequest request
    );
}
```

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

# 23. Security Model

Skills should have explicit permissions.

Example:

```json
{
  "id": "event_creation",

  "permissions": [
    "event:create"
  ]
}
```

A workflow should not automatically gain access to every registered skill.

Possible policy:

```text
Workflow
   ↓
Allowed Skills
   ↓
Allowed Tools
   ↓
Allowed Resources
```

This becomes especially important for MCP.

---

# 24. Versioning

Everything should be versionable.

```text
Workflow
Prompt
Skill
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
  "skills": [
    "venue_search@1.0"
  ]
}
```

This enables reproducibility.

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
* missing skills,
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
          ┌──────────────┼──────────────┐
          │              │              │
          ▼              ▼              ▼
     FlowRegistry   SkillRegistry   ModelRegistry
          │              │              │
          └──────────────┼──────────────┘
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
       ┌──────────┼──────────┐
       ▼          ▼          ▼
      LLM        Skill     Approval
```

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
SkillStepExecutor
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
├── skills/
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

Skill
  knows:
    capability
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
```

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
├── core/
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
│   ├── skill/
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
│   ├── skill.schema.json
│   └── model.schema.json
│
└── examples/
    ├── simple-chat/
    ├── tool-calling/
    ├── approval-flow/
    └── parallel-flow/
```

---

# 41. Design Principles

The project should follow these principles:

### 1. Declarative over imperative

Describe what should happen, not how the runtime implements it.

### 2. Model agnostic

The workflow should not depend on a specific LLM provider.

### 3. Tool agnostic

MCP is a provider, not the definition of a skill.

### 4. Deterministic where possible

Use code for deterministic logic.

### 5. AI where necessary

Use LLMs for reasoning and natural-language interpretation.

### 6. Explicit state

Every execution should have observable state.

### 7. Durable by design

Long-running workflows must survive process failures.

### 8. Version everything

Workflow, prompt, skill, schema and model configuration should be versioned.

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
Skill
Provider
Execution
```

Everything else builds on top of these.

```text
Workflow
   │
   ├── Step
   │    ├── LLM
   │    ├── Skill
   │    ├── Condition
   │    ├── Approval
   │    ├── Parallel
   │    └── Wait
   │
   ├── Prompt
   ├── Policy
   └── Version

Skill
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
 ┌─────────────────┐                  ┌─────────────────┐
 │  Model Registry │                  │  Skill Registry │
 └────────┬────────┘                  └────────┬────────┘
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
Skills        → replaceable
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
Skill Step
Condition
Execution Context
Model Registry
Skill Registry
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
new-skill.json
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

If adding a new MCP tool requires modifying the executor, the skill abstraction is leaking.

A successful implementation keeps these boundaries stable.

---

# 47. One-Sentence Definition

> **A model-agnostic, provider-independent, declarative workflow runtime for building durable AI applications from versioned workflows, prompts, skills, tools, and human decisions.**

This is the architectural foundation.

The implementation should optimize for one property above all others:

> **Adding new AI behavior should require configuration and composition, not rewriting the runtime.**

