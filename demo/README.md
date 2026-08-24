# demo

The public demo: `examples/vendor-selection`, with pages around it.

It is an application that *uses* PipeMesh, not part of PipeMesh. It holds sessions, keeps an
approver's inbox, and serves three views — none of which the runtime knows about, because none of
it is the runtime's business (§3).

```
/            a buyer's side: ask, watch, choose, wait for a manager
/approvals   the manager's side: what is stopped, and the two buttons
/source      the files behind it, read from disk by the process running them
```

## What the pages are for

**A greeting is not a workflow.** The demo hands every message to `mesh.process`, and the runtime
decides which workflow it means. When it cannot tell — a greeting, a question about the weather —
nothing is started and this application answers instead. Putting a greeting in the workflow would
mean an execution row for someone saying hello, and conversation logic inside the engine (§3, §19).

**Two processes, both visible.** The runtime drives the execution; this process serves the three
capabilities the flow calls. The panel on the right of `/` is the runtime reporting on itself —
step starts, suspensions, resumes, the terminal status — not this application narrating.

**A stop is a real stop.** `await_choice` and `manager_approval` persist the execution and release
everything. Closing the tab, restarting this process, or restarting the runtime loses nothing;
`/approvals` finds the same work waiting.

**Visitors do not collide.** The correlation key is derived from the session, so the event that
wakes one visitor's execution cannot reach another's. That is `wait` doing the isolating, not a
filter somebody remembered to write.

## Running it

Needs a runtime on `PIPEMESH_TARGET` serving `examples/vendor-selection`, and a model endpoint
that runtime can reach.

```bash
pip install -r demo/requirements.txt ./sdk/python
PIPEMESH_TARGET=localhost:8080 uvicorn demo.app.main:app --port 8000
```

One worker, deliberately: this process serves the capabilities as well as the pages, and a second
copy would register a second worker for the same three.

## Layout

```
app/main.py           routes, and nothing else
app/conversations.py  a request from the visitor's message to the order
app/trace.py          what happened to one execution, where browsers can read it
app/sources.py        the files /source shows
app/static/           three pages, vanilla JS, no build step
```
