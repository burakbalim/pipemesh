# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**PipeMesh** — a language-agnostic declarative runtime for AI workflows, implemented in Java.

Workflows are versioned JSON artifacts. The runtime compiles them into an execution graph and runs
them through pluggable providers, with durable state so an execution survives process restarts.

**Current status: first slice, stage 4 of 6.** The whole chain runs from JSON — LLM, condition,
capability, approval, resume — and survives a restart. The model and the capability are test
doubles so far; a real provider and a real MCP client are next, then observability.

```
DESIGN.md                      # full technical architecture (47 sections)
README.md                      # public overview
.claude/contracts/README.md    # index: DESIGN.md → 15 planned contracts
.claude/contracts/*.md         # one contract per implementable slice
pipemesh-core/                 # workflow model, execution contracts, runtime API
```

Read `DESIGN.md` before making architectural claims. Section numbers (§9.8, §26.1, …) are referenced
throughout the contracts and are the shared vocabulary of this project.

## Working Method

Work flows through contracts, not ad-hoc tasks:

```
/new-feature   → contract draft in .claude/contracts/
/preflight     → fills the contract's "Split Decision" section
implementation → fills "Implementation Notes" as work progresses
```

Never start implementing a slice that has no contract. If a task doesn't fit an existing contract,
write one first.

The active slice is `.claude/contracts/walking-skeleton.md` — the first end-to-end vertical slice:
`JSON Workflow → LLM → Condition → MCP → Approval → Resume → Observable execution`. Its Split
Decision defines a 6-stage build order; follow it, and do not pull later stages forward.

## Build & Test

```bash
mvn -o test                          # everything; needs Docker for the postgres module
mvn -o -pl pipemesh-core test        # core only, no Docker
mvn -o test -Dtest=ExecutionContextTest
```

`pipemesh-postgres` tests run PostgreSQL in Testcontainers, so Docker must be running. They are the
only tests that prove durability — an in-memory store cannot.

If a build hangs rather than failing, check `~/.m2/settings.xml`: an `artifactory` profile there
can point `central` at a corporate mirror that is unreachable outside its VPN. `-o` works from the
local cache. Nothing in this repository depends on that mirror.

Dependencies are deliberately few: `jackson-databind` at runtime, `junit-jupiter` for tests. Adding
one to `core/` should feel like a decision, not a convenience.

## Planned Stack

| Layer | Technology |
|---|---|
| Core runtime | Java 21, **framework-free** — no Spring, no web framework in `core/` |
| Build | Maven, multi-module |
| State store | PostgreSQL in `pipemesh-postgres` (JSONB variables, optimistic locking); in-memory impl for tests |
| MCP | `io.modelcontextprotocol.sdk:mcp`, stdio transport |
| Expressions | narrow in-house evaluator — JSONPath reads + fixed comparison grammar |
| Client boundary | gRPC (`proto/pipemesh.proto`) — designed now, implemented in a later contract |
| SDKs | Python, TypeScript, Java — generated from the proto (later contract) |

Spring integration, when it arrives, lives in a separate `spring/` module. It never becomes a
dependency of `core/`.

## Architectural Rules

These are the rules that make PipeMesh what it is. A change that violates one is wrong even if it
passes the tests — say so rather than accepting it.

- **A workflow never learns how a capability is implemented.** MCP, REST, gRPC, an in-process
  function, an external worker — all of it is registration metadata. The workflow says
  `{"type": "capability", "capability": "..."}` and nothing more (§9.8, §10).
- **`task` is not a workflow step type.** It may exist as an internal runtime concept — an execution
  unit the engine schedules — but it must not appear in the workflow DSL (§9.8).
- **Ownership, version, deployment mechanism and permissions belong to the capability registration**,
  never to the workflow step. Permission enforcement happens in the registry, not the DSL (§23).
- **No inline code in workflow definitions.** A workflow names a capability; it never carries a body
  to execute. Schemas must be closed (`additionalProperties: false`) (§23.1).
- **Business logic stays in the application.** The runtime knows *when* a capability runs, never
  *what* it does (§3).
- **The proto is the authoritative API contract.** The Java API is one binding; the gRPC service is a
  thin adapter over the same core, never a second implementation with its own semantics (§26.1).
- **Everything crossing the boundary must be serializable.** String identifiers, JSON variables, no
  in-memory handles, no `Future`, no open connections in a return value. This holds today even
  though gRPC is not implemented yet — it is what keeps the later contract from rewriting the core.
- **Runtime / SDK / Provider stay separate.** The runtime is the engine, an SDK is access to it, a
  provider is how it reaches the outside world. LangChain, OpenAI, MCP and managed agent platforms
  are all providers — optional adapters, never core dependencies (§26.2, §35).
- **Adding a step type must not require touching `WorkflowExecutor`.** New primitives arrive as new
  `StepExecutor` implementations plus a schema entry. If the engine has to change, the abstraction is
  leaking (§27, §46).

## Execution & Durability Rules

- **A step's result and the execution's new state are written in one transaction.** Otherwise a
  restart either loses the step or replays it.
- **No external call inside an open transaction.** LLM calls, MCP invocations and any provider I/O
  happen outside transaction boundaries. `core/` has no Spring, so there is no `@Transactional` to
  lean on — the boundaries are explicit and easy to break. Check this whenever provider code moves.
- **Long waits never hold a thread.** An execution waiting for approval is persisted and its
  resources released; it resumes on an external signal (§16).
- **Resume is idempotent.** The same approval decision delivered twice must not advance the
  execution twice.
- **Trace context is persisted with the state.** An execution resumed after a restart must attach to
  the same trace, or "observable execution" breaks exactly where it matters most.

## Coding Standards

- Single Responsibility: one class/function = one reason to change.
- Functions under ~30 lines, classes under ~200 lines.
- Early return over nesting; max 2 levels inside a function body.
- Meaningful names — `processData`, `handleEvent`, `doStuff` are forbidden.
- Comments explain *why*, never restate the code.
- No dead code: remove unused methods, fields, imports and variables immediately.
- Depend on interfaces, not concrete infrastructure. Where no interface exists, create it before
  adding the dependency.

The parent monorepo's Spring layering rules (Controller/Service/Repository) do not apply here —
this is a framework-free library, not a Spring service. The universal rules above do apply.

## Documentation Discipline

`DESIGN.md` is the architecture; contracts narrow it, never contradict it. When a decision changes:

1. update `DESIGN.md` (the decision and its reasoning),
2. update the affected contract(s),
3. update `README.md` if the public description changes.

Section numbers in `DESIGN.md` are load-bearing — contracts cite them. Add subsections (§26.1, §26.2)
rather than renumbering existing sections.
