"""The company's own code, reached as capabilities.

The workflow calls these "vendor_search", "budget_remaining" and "place_order"
and knows nothing else — not that they are Python, not which process they are in.
Moving one behind an MCP server later is an edit to its registration file and no
change here (DESIGN.md §9.8).

It lives apart from `app.py` because two things run it: the walkthrough next to
it, and the web demo in `demo/`. Business code with two callers and one
definition is the point of the split.
"""

from __future__ import annotations

from pipemesh import CapabilityFailure, PipeMeshWorker

CATALOGUE = [
    {"vendorId": "v-nordic", "vendor": "Nordic Supply", "amount": 8400, "leadTimeDays": 5},
    {"vendorId": "v-aegean", "vendor": "Aegean Trading", "amount": 12750, "leadTimeDays": 2},
    {"vendorId": "v-baltic", "vendor": "Baltic Works", "amount": 21900, "leadTimeDays": 1},
]

QUARTERLY_BUDGET = 15000


def vendor_search(request):
    return {"found": [dict(row, item=request["item"]) for row in CATALOGUE]}


def budget_remaining(request):
    return {"costCentre": request["costCentre"], "remaining": QUARTERLY_BUDGET, "currency": "EUR"}


def place_order(choice):
    # Declared `idempotent: false` in its registration, so recovery will never
    # repeat it: after a crash nobody can tell whether the order was placed, and
    # guessing is how a company buys the same thing twice.
    if choice["amount"] > QUARTERLY_BUDGET:
        # A code the workflow branches on, and deliberately not retryable: a rule
        # that said no does not say anything different when asked twice.
        raise CapabilityFailure(code="budget.exceeded", message="over the remaining budget")

    return {"orderId": f"PO-{choice['vendorId']}", "amount": choice["amount"]}


def serve(target: str, organization: str) -> PipeMeshWorker:
    """Register all three against a runtime, without starting.

    The caller starts it, because who owns the lifetime differs: a script stops
    when it is done, a web application when it shuts down.
    """
    worker = PipeMeshWorker(target, organization=organization)
    worker.capability("vendor_search")(vendor_search)
    worker.capability("budget_remaining")(budget_remaining)
    worker.capability("place_order")(place_order)
    return worker
