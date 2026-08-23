"""One purchase request, from the visitor's message to the order.

Everything the demo knows about a request is here. The web layer above it does
HTTP and nothing else; the runtime below it executes the flow and knows nothing
about visitors, sessions or browsers.
"""

from __future__ import annotations

import secrets
import threading
from collections import OrderedDict
from typing import Any, Mapping, Optional

from pipemesh import Approval, PipeMesh

from .trace import Trace

WORKFLOW = "vendor_selection"
COST_CENTRE = "CC-4100"

# A public demo runs until somebody stops it. Traces are held in memory, so the
# oldest ones are dropped rather than allowed to accumulate — the execution
# itself stays on disk either way, which is the whole point of the runtime.
REMEMBERED = 200


class Conversations:
    """Starts requests, follows them, and answers what they stop for."""

    def __init__(self, mesh: PipeMesh) -> None:
        self._mesh = mesh
        self._traces: OrderedDict[str, Trace] = OrderedDict()
        self._sessions: OrderedDict[str, list[str]] = OrderedDict()
        self._approvals: OrderedDict[str, dict[str, Any]] = OrderedDict()
        self._lock = threading.Lock()

    # -- starting -----------------------------------------------------------

    def start(self, session: str, message: str) -> str:
        """Run the flow for one visitor's message.

        The correlation key is derived from the session, so two visitors waiting
        at the same step are already separate: the event that wakes one cannot
        reach the other. That is the runtime's `wait` doing the isolating, not a
        filter this application remembered to write.
        """
        request_id = f"{session}-{secrets.token_hex(4)}"
        handle = self._mesh.execute(WORKFLOW, {
            "requestId": request_id,
            "message": message,
            "costCentre": COST_CENTRE,
        })

        trace = Trace()
        with self._lock:
            self._remember(handle.execution_id, trace)
            self._sessions.setdefault(session, []).append(handle.execution_id)

        threading.Thread(
            target=self._follow, args=(handle.execution_id, request_id, trace), daemon=True
        ).start()
        return handle.execution_id

    def executions_of(self, session: str) -> list[str]:
        with self._lock:
            return list(self._sessions.get(session, []))

    def trace_of(self, execution_id: str) -> Optional[Trace]:
        with self._lock:
            return self._traces.get(execution_id)

    # -- answering ----------------------------------------------------------

    def choose(self, execution_id: str, vendor_id: str) -> None:
        """Tell a waiting execution which supplier the visitor picked."""
        variables = self._mesh.get(execution_id).variables
        option = self._option(variables, vendor_id)

        # The execution is waiting under (organization, "vendor_chosen",
        # requestId). It does not know this process exists, and after a restart
        # of this process the same publish would still reach it.
        self._mesh.publish("vendor_chosen", variables["request"]["requestId"], option)

    def decide(self, execution_id: str, approved: bool, decided_by: str) -> None:
        """Record an approver's answer.

        Idempotent in the runtime: the same decision delivered twice does not
        advance the execution twice, so a double-clicked button is harmless.
        """
        self._mesh.decide(execution_id, Approval(
            approval_id=f"{execution_id}:manager_approval",
            approved=approved,
            decided_by=decided_by,
        ))

    def awaiting_approval(self) -> list[Mapping[str, Any]]:
        with self._lock:
            return list(reversed(self._approvals.values()))

    def snapshot(self, execution_id: str) -> Mapping[str, Any]:
        state = self._mesh.get(execution_id)
        return {
            "executionId": state.execution_id,
            "workflow": f"{state.workflow_id}@{state.workflow_version}",
            "status": state.status.value,
            "step": state.current_step,
            "variables": state.variables,
        }

    # -- following ----------------------------------------------------------

    def _follow(self, execution_id: str, request_id: str, trace: Trace) -> None:
        """Watch one execution for as long as it runs.

        A thread per execution, opened when the execution starts rather than
        when a browser connects: a visitor who closes the tab and comes back
        should find what happened while they were gone.
        """
        try:
            for update in self._mesh.watch(execution_id, tokens=False):
                trace.append(self._describe(execution_id, update))
                self._note_approval(execution_id, request_id, update)
        except Exception as failure:  # noqa: BLE001 — a watcher must not take the app down
            trace.append({"kind": "watch_failed", "detail": str(failure)})
        finally:
            trace.close()
            with self._lock:
                self._approvals.pop(execution_id, None)

    def _describe(self, execution_id: str, update: Any) -> dict[str, Any]:
        """Turn one update into what a browser needs to show it.

        Variables are read at the two moments a page has something new to
        display — a stop and an ending — rather than on every update, because
        most updates change nothing a visitor can see.
        """
        event: dict[str, Any] = {
            "sequence": update.sequence,
            "kind": update.kind,
            "step": update.step_id,
            "attempt": update.attempt,
        }
        if update.status is not None:
            event["status"] = update.status.value
        if update.kind in ("suspended", "finished"):
            event["variables"] = self._mesh.get(execution_id).variables
        return event

    def _note_approval(self, execution_id: str, request_id: str, update: Any) -> None:
        """Keep the approver's inbox in step with what is actually waiting.

        The inbox is this application's, not the runtime's. A runtime that kept
        one would be deciding who approves what, which is the application's
        business (§3).
        """
        if update.kind == "suspended" and update.step_id == "manager_approval":
            variables = self._mesh.get(execution_id).variables
            with self._lock:
                self._approvals[execution_id] = {
                    "executionId": execution_id,
                    "requestId": request_id,
                    "item": variables["request"]["item"],
                    "quantity": variables["request"]["quantity"],
                    "choice": variables["choice"],
                    "remaining": variables["budget"]["remaining"],
                }
            return

        if update.kind in ("resumed", "finished"):
            with self._lock:
                self._approvals.pop(execution_id, None)

    # -- housekeeping -------------------------------------------------------

    def _remember(self, execution_id: str, trace: Trace) -> None:
        self._traces[execution_id] = trace
        while len(self._traces) > REMEMBERED:
            self._traces.popitem(last=False)
        while len(self._sessions) > REMEMBERED:
            self._sessions.popitem(last=False)

    @staticmethod
    def _option(variables: Mapping[str, Any], vendor_id: str) -> Mapping[str, Any]:
        for option in variables["options"]["options"]:
            if option["vendorId"] == vendor_id:
                return option
        raise LookupError(f"the model did not offer '{vendor_id}'")
