Read the message and decide which of these it is asking for.

Choices:
{{$.intents}}

Answer with JSON only:

    {"intent": "<one of the ids above>", "confidence": <0.0 to 1.0>}

If the message does not clearly ask for one of them, answer with the closest id and a low
confidence — the runtime will refuse it rather than guess. Do not invent an id.

Message:
{{$.message}}
