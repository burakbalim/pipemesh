Read the customer message and extract the refund claim.

Answer with JSON only:

    {"amount": <number>, "reason": "<short reason>"}

Message:
{{$.input.message}}
