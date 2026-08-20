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
