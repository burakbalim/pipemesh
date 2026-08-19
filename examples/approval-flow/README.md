# approval-flow

A configuration repository, in the layout the runtime reads (DESIGN.md §31).

```text
workflows/     one JSON per workflow
models/        aliases and the protocol behind each
capabilities/  one JSON per capability registration
prompts/       group/name.version.md
schemas/       structured-output schemas
```

Two workflows share this configuration. `venue_booking` extracts a request with a model, checks it
without one, reaches a capability, and stops for a person. `refund_request` was added later and
needed no runtime change — which is the property the whole design is built around (§46).

Read `venue-booking.json` and notice what it does not say: nothing about MCP, HTTP, which vendor
serves `fast`, or where a credential comes from. A workflow names a model alias, a prompt id and a
capability; everything else is registration.

## Running it

```java
ConfigRepository config = new ConfigRepository(Path.of("examples/approval-flow"));

var models = config.modelRegistry(List.of(new OpenAiCompatibleProviderFactory()));
var prompts = config.promptRegistry();
var capabilities = config.capabilityRegistry();
```

`fast` points at a local Ollama, so nothing here needs an API key. `reasoning` names
`OPENAI_API_KEY` — the variable, never the key: config files get committed, credentials should not.

`venue_search` is registered as an MCP tool. Pointing it at a REST endpoint instead is an edit to
`capabilities/venue-search.json` and nothing else.
