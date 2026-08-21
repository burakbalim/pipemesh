# hello

The smallest configuration that runs: one workflow, one decision, two endings.

```bash
cd deploy/on-premise && docker compose up
```

```python
from pipemesh import PipeMesh

with PipeMesh("localhost:8080") as mesh:
    handle = mesh.execute("greet", {"orders": 42})
```

It needs no model, no API key and no capability worker — deliberately. A first run that asks for
a vendor account before it can do anything teaches nothing about the runtime.

`../approval-flow` is the next one: a model, a schema, an MCP tool, an approval and a resume. It
does need a key, and says which environment variable holds it.
