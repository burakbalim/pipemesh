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
```

## Running it

```bash
export OPENAI_API_KEY=...
PIPEMESH_CONFIG=$PWD/examples/vendor-selection \
  java -jar pipemesh-runtime/target/pipemesh-runtime-*.jar &

python examples/vendor-selection/app.py
```

Two steps call a model, so a key is needed. Everything else — the suppliers, the budget, the
order — is Python in `app.py`.

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

**Money is metered.** The workflow declares a budget, the model registrations declare prices, and
every execution records what it spent. An organization on a plan is also stopped at the boundary
before it starts something it cannot afford — none of which the workflow mentions.

**Repeating is refused where it matters.** `place_order` declares `idempotent: false`, so recovery
after a crash stops for a person rather than risking a second order.
