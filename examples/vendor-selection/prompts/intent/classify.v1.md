Read the message and decide which of these it is asking for.

Choices:
{{$.intents}}

Answer with JSON only:

    {"intent": "<one of the ids above>", "confidence": <0.0 to 1.0>}

A greeting, a question about how this works, or anything that is not a request to buy something
gets a low confidence — the runtime refuses it rather than guessing, and the application answers
instead. Do not invent an id.

Message:
{{$.message}}
