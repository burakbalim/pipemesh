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
kubectl apply -f dispatcher-hpa.yaml     # needs a metrics adapter reading OTLP
```

## What is deliberately not here

**No database.** This composition connects to one; it does not run one. Managed PostgreSQL, an
operator, a machine in the corner — all the same to it.

**No TLS in the processes.** It terminates at the ingress, and pods speak plaintext inside the
cluster. Moving certificates into the application gives every pod a renewal to get wrong.

**No autoscaling *targets*.** `dispatcher-hpa.yaml` shows the shape and the metric to use; ten
seconds is an example, not a recommendation. What delay is acceptable is a business decision.

**No payment.** Plans and quotas are enforced; nobody is charged. That is a separate contract and
a separate security surface.

## Why the api replica does not drive work

`PIPEMESH_DISPATCH=off` stops the driver loop. On its own that is not enough: without
`PIPEMESH_START=dispatched`, a process that does not dispatch drives what it is asked to on the
caller's thread — which is the right default for a lone process and exactly wrong here.

The two are separate questions and separate variables. Setting only the first would give you an
API replica that quietly does the dispatcher's job.

## Scaling the dispatchers

On `pipemesh.backlog.age_seconds` — how long the oldest unclaimed execution has been waiting —
rather than on how many are queued. Depth feeds back on itself and says nothing about whether
the number is a problem; age is the delay somebody is experiencing.

Both gauges are published, and **neither may be summed across replicas**. Every process reports
the same fact about the same database, so a sum multiplies it by the replica count. That is why
they carry no instance attribute.

## Live updates cross processes

An execution driven by a dispatcher is watchable through any api replica, because updates travel
over PostgreSQL `LISTEN/NOTIFY` (DESIGN.md §30.1). Two things about that channel are inherited
rather than chosen:

- **It is not durable.** A replica that is not listening misses what happened while it was down,
  and reconnects loudly rather than silently.
- **Sequence numbers belong to a stream, not to an execution.** Whichever replica serves a
  watcher numbers what it sends. Two clients watching the same execution see the same events with
  their own numbering, and `from_sequence` remains unimplemented.
