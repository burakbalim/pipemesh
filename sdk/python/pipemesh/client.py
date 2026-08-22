"""The client a Python application uses to reach a PipeMesh runtime."""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Any, Iterator, Mapping, Optional

import grpc
from google.protobuf import json_format, struct_pb2

from . import pipemesh_pb2 as pb
from . import pipemesh_pb2_grpc as rpc


class ExecutionStatus(str, Enum):
    """Where an execution stands.

    A plain string enum rather than the generated one, so ``status ==
    "WAITING"`` reads the way a Python developer expects and printing it in a
    log gives a word rather than a number.
    """

    CREATED = "CREATED"
    RUNNING = "RUNNING"
    WAITING = "WAITING"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"

    @classmethod
    def _from_wire(cls, value: int) -> "ExecutionStatus":
        name = pb.ExecutionStatus.Name(value).removeprefix("EXECUTION_STATUS_")
        return cls(name)

    @property
    def is_waiting(self) -> bool:
        return self is ExecutionStatus.WAITING

    @property
    def is_terminal(self) -> bool:
        return self in (
            ExecutionStatus.COMPLETED,
            ExecutionStatus.FAILED,
            ExecutionStatus.CANCELLED,
        )


@dataclass(frozen=True)
class ExecutionHandle:
    """Where an execution got to, as of the call that returned it."""

    execution_id: str
    status: ExecutionStatus
    current_step: Optional[str]


@dataclass(frozen=True)
class ExecutionSnapshot:
    """A read of an execution, variables included."""

    execution_id: str
    organization: str
    workflow_id: str
    workflow_version: str
    status: ExecutionStatus
    current_step: Optional[str]
    variables: Mapping[str, Any]


@dataclass(frozen=True)
class Approval:
    """A decision a person made about a waiting execution."""

    approval_id: str
    approved: bool
    decided_by: str = ""
    comment: str = ""


@dataclass(frozen=True)
class Update:
    """Something that happened to an execution being watched.

    ``kind`` is one of ``started``, ``step_started``, ``step_finished``,
    ``suspended``, ``resumed``, ``recovered``, ``finished`` or ``token``.

    ``attempt`` counts from 1 on ``step_started``: a retry is a real second
    start rather than one long step. ``repeated`` says whether a ``recovered``
    execution could carry on, or stopped for a person.
    """

    sequence: int
    kind: str
    step_id: Optional[str] = None
    text: Optional[str] = None
    status: Optional[ExecutionStatus] = None
    attempt: Optional[int] = None
    repeated: Optional[bool] = None
    reason: Optional[str] = None


class PipeMeshError(RuntimeError):
    """A call the runtime refused.

    Carries the gRPC status code, because the useful question after a failure is
    whether it was this caller's mistake or the server's — and the answer decides
    whether retrying makes any sense.
    """

    def __init__(self, message: str, code: grpc.StatusCode) -> None:
        super().__init__(message)
        self.code = code

    @property
    def not_found(self) -> bool:
        return self.code is grpc.StatusCode.NOT_FOUND

    @property
    def invalid(self) -> bool:
        return self.code is grpc.StatusCode.INVALID_ARGUMENT

    @property
    def unimplemented(self) -> bool:
        return self.code is grpc.StatusCode.UNIMPLEMENTED

    @property
    def failed_precondition(self) -> bool:
        """The call was fine; the runtime could not act on it as asked."""
        return self.code is grpc.StatusCode.FAILED_PRECONDITION


class PipeMesh:
    """A connection to a PipeMesh runtime.

    ::

        with PipeMesh("localhost:8080") as mesh:
            handle = mesh.execute("venue_booking", {"price": 250})
            if handle.status.is_waiting:
                mesh.approve(handle.execution_id, f"{handle.execution_id}:approval")
    """

    def __init__(
        self,
        target: str = "localhost:8080",
        *,
        organization: str = "default",
        channel: Optional[grpc.Channel] = None,
    ) -> None:
        self._organization = organization
        self._owns_channel = channel is None
        self._channel = channel or grpc.insecure_channel(target)
        self._stub = rpc.PipeMeshStub(self._channel)

    def execute(
        self,
        workflow_id: str,
        payload: Optional[Mapping[str, Any]] = None,
        *,
        organization: Optional[str] = None,
        traceparent: str = "",
        version: Optional[str] = None,
    ) -> ExecutionHandle:
        """Run a named workflow.

        Returns as soon as the execution stops moving — finished, or waiting for
        a person. Waiting costs nothing on the server, so a workflow that needs
        an approval returns promptly with ``status.is_waiting`` rather than
        holding the call open (DESIGN.md §26.4).

        ``version`` pins the run to one workflow version. Left out, the newest
        registered version is chosen once and written to the execution's record,
        so a deploy landing mid-run does not move it (§24).
        """
        request = pb.StartExecutionRequest(
            workflow_id=workflow_id,
            input=_to_struct(payload),
            organization_id=organization or self._organization,
            traceparent=traceparent,
            workflow_version=version or "",
        )
        return _handle(self._call(self._stub.StartExecution, request))

    def process(
        self,
        message: str,
        payload: Optional[Mapping[str, Any]] = None,
        *,
        organization: Optional[str] = None,
        traceparent: str = "",
    ) -> ExecutionHandle:
        """Let the runtime read the message and run whatever it asks for.

        Raises :class:`PipeMeshError` with ``failed_precondition`` when the
        message does not settle on an intent. That is not the same as a bad
        request: the call was fine, the runtime could not tell what to do with
        it, and retrying the same words will not help.
        """
        request = pb.ProcessMessageRequest(
            message=message,
            input=_to_struct(payload),
            organization_id=organization or self._organization,
            traceparent=traceparent,
        )
        return _handle(self._call(self._stub.ProcessMessage, request))

    def approve(
        self,
        execution_id: str,
        approval_id: str,
        *,
        decided_by: str = "",
        comment: str = "",
    ) -> ExecutionHandle:
        return self.decide(execution_id, Approval(approval_id, True, decided_by, comment))

    def reject(
        self,
        execution_id: str,
        approval_id: str,
        *,
        decided_by: str = "",
        comment: str = "",
    ) -> ExecutionHandle:
        return self.decide(execution_id, Approval(approval_id, False, decided_by, comment))

    def decide(self, execution_id: str, approval: Approval) -> ExecutionHandle:
        """Deliver a decision.

        Delivering the same one twice is safe: the runtime advances an execution
        once and reports where it stands the second time.
        """
        request = pb.SubmitApprovalRequest(
            execution_id=execution_id,
            approval_id=approval.approval_id,
            approved=approval.approved,
            decided_by=approval.decided_by,
            comment=approval.comment,
        )
        return _handle(self._call(self._stub.SubmitApproval, request))

    def get(self, execution_id: str) -> ExecutionSnapshot:
        snapshot = self._call(
            self._stub.GetExecution, pb.GetExecutionRequest(execution_id=execution_id))

        return ExecutionSnapshot(
            execution_id=snapshot.execution_id,
            organization=snapshot.organization_id,
            workflow_id=snapshot.workflow_id,
            workflow_version=snapshot.workflow_version,
            status=ExecutionStatus._from_wire(snapshot.status),
            current_step=snapshot.current_step_id or None,
            variables=json_format.MessageToDict(snapshot.variables),
        )

    def watch(
        self,
        execution_id: str,
        *,
        tokens: bool = True,
        progress: bool = True,
        from_step: int = 0,
    ) -> Iterator[Update]:
        """Return what happens to an execution, until it ends.

        The subscription is opened by this call, not by the first item read from
        it. That distinction matters: a generator that subscribed lazily would
        miss everything between asking to watch and getting round to reading, and
        the loss would be silent.

        The first item is always ``kind == "started"`` and carries the status as
        of the moment the watch began — the point a caller can act from, knowing
        nothing after it will be missed. The stream closes itself when the
        execution reaches a terminal status, so a ``for`` loop over it ends on
        its own.

        ``tokens`` and ``progress`` turn off the two noisy parts: model output as
        it is produced, and ``step_started``. Status updates cannot be turned off
        — a stream that could omit ``finished`` would leave a caller waiting for
        something already over. Declining leaves gaps in ``sequence``, which is
        how filtering stays distinguishable from loss.

        Pass ``from_step`` to pick up where a dropped connection left off: it is
        how many step history entries you have already seen. A count rather than
        a sequence number, because a sequence belongs to one stream while step
        history is durable and ordered, so "the first N" means the same thing
        whichever replica answers.

        Tokens are not replayed — they are never stored. A resumed stream tells
        you what happened, not everything you would have seen.
        """
        exclude = []
        if not tokens:
            exclude.append(pb.UPDATE_KIND_TOKEN)
        if not progress:
            exclude.append(pb.UPDATE_KIND_PROGRESS)

        request = pb.WatchExecutionRequest(
            execution_id=execution_id, exclude=exclude, from_step=from_step)
        try:
            stream = self._stub.WatchExecution(request)
            first = next(stream)
        except StopIteration:
            return iter(())
        except grpc.RpcError as failure:
            raise PipeMeshError(failure.details(), failure.code()) from None

        return self._updates(first, stream)

    def _updates(self, first: Any, stream: Any) -> Iterator[Update]:
        yield _update(first)
        try:
            for update in stream:
                yield _update(update)
        except grpc.RpcError as failure:
            raise PipeMeshError(failure.details(), failure.code()) from None

    def close(self) -> None:
        if self._owns_channel:
            self._channel.close()

    def __enter__(self) -> "PipeMesh":
        return self

    def __exit__(self, *_: Any) -> None:
        self.close()

    def _call(self, method: Any, request: Any) -> Any:
        try:
            return method(request)
        except grpc.RpcError as failure:
            raise PipeMeshError(failure.details(), failure.code()) from None


def _to_struct(payload: Optional[Mapping[str, Any]]) -> struct_pb2.Struct:
    struct = struct_pb2.Struct()
    if payload:
        struct.update(dict(payload))
    return struct


def _handle(reply: Any) -> ExecutionHandle:
    return ExecutionHandle(
        execution_id=reply.execution_id,
        status=ExecutionStatus._from_wire(reply.status),
        current_step=reply.current_step_id or None,
    )


def _update(update: Any) -> Update:
    kind = update.WhichOneof("update")
    if kind == "step_started":
        return Update(update.sequence, kind,
                      step_id=update.step_started.step_id,
                      attempt=update.step_started.attempt)
    if kind == "recovered":
        return Update(update.sequence, kind,
                      step_id=update.recovered.step_id,
                      repeated=update.recovered.repeated,
                      reason=update.recovered.reason or None)
    if kind == "step_finished":
        return Update(update.sequence, kind, step_id=update.step_finished.step_id)
    if kind == "suspended":
        return Update(update.sequence, kind, step_id=update.suspended.step_id)
    if kind == "resumed":
        return Update(update.sequence, kind, step_id=update.resumed.step_id)
    if kind == "started":
        return Update(update.sequence, kind,
                      status=ExecutionStatus._from_wire(update.started.execution.status))
    if kind == "finished":
        return Update(
            update.sequence, kind,
            status=ExecutionStatus._from_wire(update.finished.status))
    if kind == "token":
        return Update(update.sequence, kind,
                      step_id=update.token.step_id, text=update.token.text)
    return Update(update.sequence, kind or "unknown")
