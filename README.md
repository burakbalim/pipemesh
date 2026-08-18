# PipeMesh

**A declarative, model-agnostic runtime for building composable AI workflows.**

Provider-independent and durable by design: workflows survive process failures, resume where they
left off, and stay observable end to end.

PipeMesh treats the workflow itself as a first-class, versioned, declarative artifact. Application
behavior is described in JSON; the runtime interprets that definition and executes it through
pluggable model providers, skills and tools.

```text
Workflow JSON  →  WHAT should happen
Runtime        →  HOW it happens
```

> **Status:** Design phase (v0.1) — no implementation yet. See [DESIGN.md](DESIGN.md) for the full
> technical architecture.

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
| **Step** | A unit of execution: `llm`, `skill`, `condition`, `approval`, `parallel`, `transform`, `wait` |
| **Skill** | An abstract capability, backed by MCP, REST, or in-process code |
| **Provider** | Pluggable model / messaging backend |
| **Execution state** | Persisted, observable, resumable |

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
        ┌───────────────┼────────────────┐
        ▼               ▼                ▼
      Model           Skill           Approval
     Provider        Registry          Gateway
        │               │                │
        ▼               ▼                ▼
       LLM          MCP / API /      Human / System
                     Service
```

## Planned project structure

```text
core/           # workflow, execution, state, scheduler, expressions
providers/      # messaging, models, tools
integrations/   # mcp, http, messaging
registry/       # workflow, skill, prompt, model
observability/  # tracing, metrics, logging
schemas/        # workflow / skill / model JSON schemas
examples/       # simple-chat, tool-calling, approval-flow, parallel-flow
```

## Roadmap

- **Phase 1** — Workflow JSON, intent resolution, LLM step, skill step, condition, execution
  context, model & skill registry, messaging provider
- **Phase 2** — MCP, structured output, retry, timeout, streaming, prompt registry
- **Phase 3** — Persistent state, human approval, resume, parallel execution, event-driven execution
- **Phase 4** — OpenTelemetry, workflow versioning, evaluation, cost tracking, model routing,
  distributed workers

## Success criteria

Adding a new AI behavior must require **configuration and composition, not runtime changes**:

```text
new-workflow.json + new-prompt.md + new-skill.json
```

with no edits to `WorkflowExecutor`, the scheduler, model providers, MCP integration, state
management or observability. If the engine has to change, the abstraction is leaking.

## Documentation

- [DESIGN.md](DESIGN.md) — full technical architecture & design document (47 sections)

## License

Licensed under the [Apache License 2.0](LICENSE).
