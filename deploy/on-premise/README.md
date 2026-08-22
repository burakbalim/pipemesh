# On-premise PipeMesh

One node: a runtime and a database, on a machine you own.

```bash
cd deploy/on-premise
docker compose up
```

Then, from anywhere:

```python
from pipemesh import PipeMesh

with PipeMesh("localhost:8080") as mesh:
    handle = mesh.execute("greet", {"orders": 42})
```

The default configuration is `examples/hello` — one workflow, no model, no key. Point at your own
with `PIPEMESH_CONFIG_DIR=/path/to/your/workflows`.

## What this install is, and is not

**It authenticates nobody.** Every caller is anonymous, so organizations are not isolated from
one another — the runtime says so at startup, loudly. For a single-tenant install that is the
honest answer rather than a gap: there is one tenant, and the isolation would be enforcing a
boundary that does not exist (DESIGN.md §22.2).

To identify callers, supply a `PrincipalResolver`. That is what the cloud composition does, and
nothing in the runtime changes to accommodate either choice.

**There is no metering.** No plans, no quotas, no console. On-premise is paid for by contract,
not by counting — and if a deployment ever does need counting, it is the same `QuotaInterceptor`
with a locally configured plan. A composition, not a branch.

**And no billing.** If you do run the console here — some installs want it for keys and usage —
set `CONSOLE_DEFAULT_PLAN=unlimited` so accounts are not capped at the demo plan, and configure
no payment provider. Checkout and the webhook endpoint are then absent rather than refusing:
an unconfigured deployment answering 200 to an unsigned POST looks exactly like a working one.

**The database is yours.** The `postgres` service here exists because a single node has to get
one somewhere. Point `PIPEMESH_DB_URL` at an existing server and delete the service; the runtime
connects to a database, it does not operate one.

## Configuration

| Variable | Meaning | Default |
|---|---|---|
| `PIPEMESH_CONFIG` | directory of workflows, models, capabilities, prompts | `/etc/pipemesh` |
| `PIPEMESH_PORT` | gRPC port | `8080` |
| `PIPEMESH_DB_URL` | an existing PostgreSQL; without it, state is in memory | — |
| `PIPEMESH_DB_USER`, `PIPEMESH_DB_PASSWORD` | | `pipemesh` |
| `PIPEMESH_RECOVERY_INTERVAL` | how often stuck executions are swept | `1M` |
| `PIPEMESH_DISPATCH` | whether this process drives executions | `on` |
| `PIPEMESH_DISPATCH_INTERVAL` | how often it looks for work | `1S` |

Model API keys are named by the model configuration and read from the environment. A key never
belongs in a file that gets committed.

## Migrating separately

```bash
docker compose run --rm runtime --migrate-only
```

Startup migrates anyway, and does so safely even with several replicas — an advisory lock makes
the losers wait rather than fail. This exists for deployments that would rather migrate as their
own step.
