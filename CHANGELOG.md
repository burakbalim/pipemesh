# Changelog

Versions promise something about `proto/pipemesh.proto`, because that file is the authoritative
contract (DESIGN.md §26.1):

| | meaning |
|---|---|
| **major** | a published field or RPC changed meaning or went away |
| **minor** | something was added — a field, an RPC, an enum value |
| **patch** | the proto did not change |

An SDK is compatible with a **range**, not with one runtime version: proto3 ignores fields it
does not know, and unknown update types are already tested not to break a client. So lifting five
packages together is never required.

`latest` is not published.

## Unreleased

### Proto

- `StartExecutionRequest.workflow_version` — pin a run to one workflow version (#9)
- `WatchExecutionRequest.exclude` and `UpdateKind` — decline tokens or progress (#20)
- `ExecutionUpdate.step_started`, `ExecutionUpdate.recovered` — progress while a step runs (#20)
- `WatchExecutionRequest.from_step` — resume a dropped stream (#26)
- `PublishEvent` — wake an execution waiting on an event, from a remote client (#27)
- `WatchExecutionRequest.from_sequence` is deprecated; it was never implemented (#26)

### Added

- The runtime as a runnable process, with an on-premise single-node compose (#21)
- Cross-process watching over `LISTEN/NOTIFY`, and a three-deployment cloud composition (#22)
- Console: accounts, API keys, plans, quota, and a demo that takes the production path (#19)
- Payment as an optional provider; absent entirely on an install without one (#23)
- Backlog age as the signal to scale drivers on (#24)
- `examples/vendor-selection` — a whole flow: model, capabilities, a choice, an approval
