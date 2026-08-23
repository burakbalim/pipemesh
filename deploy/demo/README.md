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
| `PROXY_NETWORK` | the docker network the reverse proxy is already on, so it can reach these containers |
| `DEMO_HOST`, `CONSOLE_HOST` | the hostnames those two answer on |
| `CERT_RESOLVER` | the proxy's ACME resolver; defaults to `letsencrypt` |
| `PIPEMESH_DB_URL` | JDBC URL of the existing PostgreSQL, e.g. `jdbc:postgresql://host:5432/pipemesh` |
| `PIPEMESH_DB_USER`, `PIPEMESH_DB_PASSWORD` | its credentials |
| `CONSOLE_BASE_URL` | where the console is reachable, used in verification links |
| `GROQ_API_KEY` | whatever key LiteLLM uses upstream |
| `LITELLM_MASTER_KEY` | the key the runtime presents to LiteLLM |

The runtime migrates the schema on start, so an empty database is enough.

`CONSOLE_CLOUD` defaults to `false`, which sends verification links to the log — right while you
are testing sign-up yourself. Turning it on demands a real mail sender and refuses to start
without one. Turn it on before anybody outside can reach this.

## Routing

Only `demo` and `console` are reachable from outside. The runtime and the model proxy stay on the
internal network — the runtime's gRPC port is not a public interface, and a model proxy holding an
upstream key certainly is not.

The labels assume a Traefik with `http` and `https` entrypoints and an ACME resolver, which is
what almost every one-host setup already has. Both services get an HTTP router that redirects to
HTTPS and an HTTPS router with TLS; nothing about them is specific to one installation, which is
why the network name and the hostnames come from the environment.

A DNS-01 resolver issues certificates without the host being reachable on port 80 — worth knowing
if the names sit behind a CDN.

**The demo's live view is a long-lived SSE stream.** Anything in front of it that buffers
responses will hold the whole stream until the execution ends, which is the one failure that
makes the page look broken rather than slow. The application already sends `Cache-Control:
no-cache` and `X-Accel-Buffering: no`; if the execution panel still fills in all at once instead
of line by line, that is what to look at.

## Bringing it up

The compose pulls `:staging` images from GHCR, published by `.github/workflows/build.yml` on every
push to master. It builds nothing on the host, and it reads nothing from the host: the workflows
and the proxy's configuration are copied out of the demo image into volumes by a `config` service
that runs once and exits.

That is deliberate. A relative bind mount assumes the deployment left a checkout beside the compose
file, and a platform that copies only the compose file leaves Docker to invent an empty directory
at each missing source — the runtime then starts against a config directory with no workflows in
it, and nothing says so. Everything comes out of an image instead.

Deploying is a person pressing a button. There is no trigger in CI on purpose: this repository is
public, and a trigger would name the host it deploys to and the secrets it uses.

## The model is the part to check first

`litellm.yaml` names two Groq models, both chosen because they declare `structured_outputs` —
which is the property that matters, not size. A model offering only `json_mode` returns *some*
JSON rather than the shape that was asked for, and the difference shows up as three retries and
a failed step.

Which models exist there changes. The first configuration named two that had already been
withdrawn, so confirm the list against your own key before showing this to anyone:

```bash
curl $LITELLM/v1/models -H "Authorization: Bearer $LITELLM_MASTER_KEY"
```

The runtime asks for structured output and validates the answer at the step boundary either way,
the prompts ask for JSON in words as well, and both model steps retry. A model that cannot produce
the shape will still fail — visibly, at the step that asked — rather than quietly producing
something odd three steps later.

## What is not here

No PostgreSQL. This stack connects to a database, it does not operate one — the same reason
`deploy/cloud/` does not ship one either.
