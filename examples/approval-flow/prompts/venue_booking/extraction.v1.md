Extract a venue booking request from the message below.

Answer with JSON only, matching this shape:

    {"valid": true, "location": "<city>", "people": <number>}

Set `valid` to false if the message is not a booking request.

Message:
{{$.input.message}}
