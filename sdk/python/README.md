# pipemesh (Python)

A client for a running PipeMesh runtime. It does not execute workflows — the runtime does that, and
an SDK's job is to reach it (DESIGN.md §26.2).

```python
from pipemesh import PipeMesh

with PipeMesh("localhost:8080", organization="acme") as mesh:
    handle = mesh.execute("venue_booking", {"price": 250})

    if handle.status.is_waiting:
        mesh.approve(handle.execution_id, f"{handle.execution_id}:approval", decided_by="burak")
```

`execute` returns as soon as the execution stops moving — finished, or waiting for a person. A
workflow that waits three days does not hold the call open for three days; waiting costs nothing on
the server, and the handle says so.

## Watching one as it runs

```python
for update in mesh.watch(handle.execution_id):
    if update.kind == "token":
        print(update.text, end="", flush=True)
```

The subscription opens when `watch()` is called, not when you first read from it — a lazy
subscription would miss whatever happened in between, silently. The first item is always
`kind == "started"` with the status as of that moment: the point you can act from, knowing nothing
after it will be missed. The stream ends itself when the execution does.

## Errors

```python
try:
    mesh.get("no-such-execution")
except PipeMeshError as failure:
    if failure.not_found:
        ...
```

The status code is kept because the useful question after a failure is whether it was this caller's
mistake or the server's, and the answer decides whether retrying makes sense.

## Reaching a deployment that authenticates

```python
mesh = PipeMesh("api.example.com:443", api_key="pm_...")   # or PIPEMESH_API_KEY
```

The key travels in call metadata, never in a request body: a request carrying its own answer to
"who am I" has not been authenticated, it has been asked politely. It identifies an organization,
so the runtime knows whose executions these are — and against an install that identifies nobody
there is nothing to send, and an absent key changes nothing.

Workers take the same option, and need it for the same reason: a worker's registration is bound
to an organization too.

Sending a key over a plaintext connection warns rather than refuses. Plaintext is right on a
laptop and a leak anywhere else.

## Serving a LangChain chain

A chain reaches the runtime the same way any other application code does — over the worker
connection — so a workflow calls it as a plain capability and never learns what is behind it:

```python
from pipemesh import PipeMeshWorker
from pipemesh.langchain import serve

worker = PipeMeshWorker("localhost:8080", organization="acme")
serve(worker, "summarize", summarize_chain, field="article")
worker.run()
```

```json
{"type": "capability", "capability": "summarize", "input": "$.article", "output": "summary"}
```

`pipemesh.langchain` does not import `langchain`. It accepts anything with an `invoke()` method,
which is what LangChain's `Runnable` and `BaseTool` both have — the ecosystem is worth reaching,
the dependency is not worth taking (DESIGN.md §35). `field` names which part of the input to hand
the chain; left out, the chain gets the whole object.

## Regenerating the stubs

The generated modules are committed so that installing this package needs no protoc. Regenerate
after changing `proto/pipemesh.proto`:

```bash
python -m grpc_tools.protoc -I../../proto \
    --python_out=pipemesh --grpc_python_out=pipemesh ../../proto/pipemesh.proto
sed -i '' 's/^import pipemesh_pb2/from . import pipemesh_pb2/' pipemesh/pipemesh_pb2_grpc.py
```

## Tests

They start the real Java runtime in a child process and talk to it over a socket, so what is tested
is the wire rather than a mock of it.

```bash
mvn -pl pipemesh-grpc -am -DskipTests install
mvn -pl pipemesh-grpc dependency:build-classpath -Dmdep.outputFile=target/test-classpath.txt
python -m venv .venv && ./.venv/bin/pip install -e ".[dev]"
./.venv/bin/python -m pytest tests
```
