"""A purchase request, from the message to the order.

Run it:

    export OPENAI_API_KEY=...
    java -jar pipemesh-runtime/target/pipemesh-runtime-*.jar &   # PIPEMESH_CONFIG=this directory
    python examples/vendor-selection/app.py

What is here is everything the company writes:

  * three capabilities, served from this process
  * starting one request
  * watching it, and answering when it stops for a person

The flow itself is not here. It is `workflows/vendor-selection.json`, and moving
the approval threshold from ten thousand to fifty thousand is editing that file —
not deploying this one.
"""

from __future__ import annotations

import os
import sys
import threading
import time

import procurement
from pipemesh import PipeMesh

RUNTIME = os.environ.get("PIPEMESH_TARGET", "localhost:8080")
ORGANIZATION = os.environ.get("PIPEMESH_ORGANIZATION", "acme")

# Against a deployment that authenticates, set PIPEMESH_API_KEY — the SDK reads
# it and sends it with every call. A single-node install identifies nobody, so
# there is nothing to send and nothing here changes.


# The three capabilities this flow calls live in `procurement.py`, because the
# web demo under `demo/` serves the same ones. Read that file for what the
# company actually wrote; what is left here is how a script drives it.

worker = procurement.serve(RUNTIME, ORGANIZATION)


# ---------------------------------------------------------------------------
# One request, start to finish.
# ---------------------------------------------------------------------------

def run(request_id: str, message: str, cost_centre: str) -> None:
    with PipeMesh(RUNTIME, organization=ORGANIZATION) as mesh:
        handle = mesh.execute("vendor_selection", {
            "requestId": request_id,
            "message": message,
            "costCentre": cost_centre,
        })
        print(f"started {handle.execution_id}")

        # Watching is a view of an execution that is already running, not a
        # second way of running it. Dropping out of this loop changes nothing
        # about the execution.
        for update in mesh.watch(handle.execution_id, tokens=False):
            if update.kind == "step_started":
                print(f"  → {update.step_id}")

            if update.kind == "suspended":
                on_suspended(mesh, handle.execution_id, update.step_id)
                continue

            if update.kind == "finished":
                print(f"finished: {update.status.name}")
                show(mesh, handle.execution_id)
                return


def on_suspended(mesh: PipeMesh, execution_id: str, step_id: str) -> None:
    """Answer whatever the execution stopped for.

    In a real application these are two different people and two different
    moments — a form, an email, days apart. Nothing is held open in between: the
    execution is on disk, and this process could be replaced before either
    arrives.
    """
    snapshot = mesh.get(execution_id)

    if step_id == "await_choice":
        options = snapshot.variables["options"]
        for option in options["options"]:
            print(f"     {option['vendorId']}  {option['vendor']}  "
                  f"{option['amount']} EUR  {option['leadTimeDays']}d — {option['why']}")
        print(f"     recommended: {options['recommended']}")

        chosen = next(option for option in options["options"]
                      if option["vendorId"] == options["recommended"])

        # The execution is waiting under (organization, "vendor_chosen",
        # requestId). It does not know this process exists.
        mesh.publish("vendor_chosen", snapshot.variables["request"]["requestId"], chosen)

    elif step_id == "manager_approval":
        approval = pending_approval(mesh, execution_id)
        print(f"     manager approving {snapshot.variables['choice']['amount']} EUR")
        # Idempotent: the same decision delivered twice does not advance the
        # execution twice, so a retrying webhook is harmless.
        mesh.approve(execution_id, approval, decided_by="manager@acme.com")


def pending_approval(mesh: PipeMesh, execution_id: str) -> str:
    """The runtime names an approval after the execution and the step."""
    return f"{execution_id}:manager_approval"


def show(mesh: PipeMesh, execution_id: str) -> None:
    variables = mesh.get(execution_id).variables
    if "order" in variables:
        print(f"  order {variables['order']['orderId']} for {variables['order']['amount']} EUR")


def main() -> None:
    if not os.environ.get("OPENAI_API_KEY"):
        sys.exit("OPENAI_API_KEY is not set; two steps of this flow call a model.")

    worker.start()
    time.sleep(0.3)          # let the worker register before the first capability call
    try:
        run(
            request_id=f"REQ-{int(time.time())}",
            message="We need 40 replacement bearings for line 3, ideally this week.",
            cost_centre="CC-4100",
        )
    finally:
        worker.stop()


if __name__ == "__main__":
    main()
