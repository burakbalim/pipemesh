# vendor-selection

A purchase request, from the message somebody typed to the order that gets placed.

```
understand (llm)                    reads the request into a shape
   ↓
gather (parallel) ──┬── vendor_search      capability
                    └── budget_remaining   capability
   ↓  join
propose (llm)                       shortlists, with a schema it must fit
   ↓
await_choice (wait)                 somebody picks one — hours or days
   ↓
needs_approval (condition)          over ten thousand?
   ├── yes → manager_approval (human_approval)
   └── no  ↓
place_order (capability)            idempotent: false
   │
   └── refused → over_budget          onFailure: goto
```

## Running it

`models/models.json` points both aliases at an OpenAI-compatible endpoint. Anything that speaks
that protocol will do — OpenAI, a LiteLLM proxy, Ollama on your own machine — so edit the base URL
and the variable the key comes from, then:

```bash
export LITELLM_KEY=...
PIPEMESH_CONFIG=$PWD/examples/vendor-selection \
  java -jar pipemesh-runtime/target/pipemesh-runtime-*.jar &

PYTHONPATH=examples/vendor-selection python examples/vendor-selection/app.py
```

Two steps call a model, so a key is needed. Everything else — the suppliers, the budget, the
order — is Python in `procurement.py`, which `app.py` and the web demo under `demo/` both serve.

## What this is meant to show

**The flow is a file.** Moving the approval threshold from ten thousand to fifty thousand, or
adding a second approver, is an edit to `workflows/vendor-selection.json`. `app.py` is not
redeployed and does not know the threshold exists.

**A capability hides its transport.** `vendor_search` is a Python function here. Its registration
says `{"type": "worker"}`; changing that to `{"type": "mcp", "server": "suppliers", ...}` moves it
behind an MCP server, and no line of `app.py` or the workflow changes (§9.8).

**Waiting costs nothing.** `await_choice` and `manager_approval` both suspend the execution to
disk. The process running `app.py` can be replaced between them; the execution does not notice,
and neither call is held open.

**Two kinds of stopping, on purpose.** An approval is yes-or-no and knows which execution it
belongs to. A choice among options does not — so it is an *event*, matched on the request id the
execution filed itself under. That is why `place_order` gets `mesh.publish(...)` and the manager
gets `mesh.approve(...)`.

**Spending is bounded.** The workflow declares a budget in model calls and tokens, and every
execution records what it used. Registering prices turns that into money — and a workflow with a
money budget is refused a model whose price nobody registered, because an unpriced model is not a
free one. These registrations state no price, so the money budget is left off rather than
guessed at.

**A refusal is an outcome.** `place_order` says no above the remaining budget. The step declares
where a failure goes, so an approved purchase that the company's own code then refuses ends at
`over_budget` — a state somebody can read — rather than as an execution that died.

**Repeating is refused where it matters.** `place_order` declares `idempotent: false`, so recovery
after a crash stops for a person rather than risking a second order.
