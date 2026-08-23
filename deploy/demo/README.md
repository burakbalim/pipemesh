# The demo stack

Four containers against a database you already run: the runtime, the console, the demo, and a
LiteLLM proxy in front of whatever model you point it at.

This is not a third deployment strategy. `deploy/on-premise/` is a single node somebody else
operates; `deploy/cloud/` is the scalable one. This is that second one wired for a single host,
and the only thing it adds is the demo.

## Before bringing it up

Set these where the deployment holds its environment. None of them is in this repository, and none
of them should be:

| Variable | What it is |
|---|---|
| `PIPEMESH_DB_URL` | JDBC URL of the existing PostgreSQL, e.g. `jdbc:postgresql://host:5432/pipemesh` |
| `PIPEMESH_DB_USER`, `PIPEMESH_DB_PASSWORD` | its credentials |
| `CONSOLE_BASE_URL` | where the console is reachable, used in verification links |
| `GROQ_API_KEY` | whatever key LiteLLM uses upstream |
| `LITELLM_MASTER_KEY` | the key the runtime presents to LiteLLM |

The runtime migrates the schema on start, so an empty database is enough.

`CONSOLE_CLOUD` defaults to `false`, which sends verification links to the log — right while you
are testing sign-up yourself. Turning it on demands a real mail sender and refuses to start
without one. Turn it on before anybody outside can reach this.

## Bringing it up

The compose pulls `:staging` images from GHCR, published by `.github/workflows/build.yml` on every
push to master. It builds nothing on the host.

Deploying is a person pressing a button. There is no trigger in CI on purpose: this repository is
public, and a trigger would name the host it deploys to and the secrets it uses.

## What is not here

No PostgreSQL. This stack connects to a database, it does not operate one — the same reason
`deploy/cloud/` does not ship one either.
