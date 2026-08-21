# Cloud PipeMesh

Three deployments that scale apart, one image between two of them, and a database somebody else
operates.

```text
api           PIPEMESH_DISPATCH=off   serves gRPC, drives nothing   scales with request load
              PIPEMESH_START=dispatched
dispatcher    PIPEMESH_DISPATCH=on    drives executions             scales with queue depth
console       Spring                  identity, plans, quota, UI    scales with request load
```

`api` and `dispatcher` are the **same image**. The difference is two environment variables,
because a deployment difference is a composition and never a branch.

```bash
kubectl apply -f namespace.yaml
kubectl apply -f secrets.example.yaml   # after replacing every value in it
kubectl apply -f migrate-job.yaml
kubectl apply -f api.yaml -f dispatcher.yaml -f console.yaml -f ingress.yaml
```

## What is deliberately not here

**No database.** This composition connects to one; it does not run one. Managed PostgreSQL, an
operator, a machine in the corner — all the same to it.

**No TLS in the processes.** It terminates at the ingress, and pods speak plaintext inside the
cluster. Moving certificates into the application gives every pod a renewal to get wrong.

**No autoscaling policy.** The `HorizontalPodAutoscaler` targets are an operations decision, and
queue depth as a metric is observability work (DESIGN.md §22.1).

**No payment.** Plans and quotas are enforced; nobody is charged. That is a separate contract and
a separate security surface.

## Why the api replica does not drive work

`PIPEMESH_DISPATCH=off` stops the driver loop. On its own that is not enough: without
`PIPEMESH_START=dispatched`, a process that does not dispatch drives what it is asked to on the
caller's thread — which is the right default for a lone process and exactly wrong here.

The two are separate questions and separate variables. Setting only the first would give you an
API replica that quietly does the dispatcher's job.

## Live updates cross processes

An execution driven by a dispatcher is watchable through any api replica, because updates travel
over PostgreSQL `LISTEN/NOTIFY` (DESIGN.md §30.1). Two things about that channel are inherited
rather than chosen:

- **It is not durable.** A replica that is not listening misses what happened while it was down,
  and reconnects loudly rather than silently.
- **Sequence numbers belong to a stream, not to an execution.** Whichever replica serves a
  watcher numbers what it sends. Two clients watching the same execution see the same events with
  their own numbering, and `from_sequence` remains unimplemented.
