"""Serves capabilities from inside a Python application.

The runtime does not reach into this process; this process opens the connection
and the runtime pushes invocations down it. A worker therefore needs no
reachable address, certificate or firewall exception (DESIGN.md §26.1).
"""

from __future__ import annotations

import queue
import sys
import threading
import time
import traceback
from concurrent import futures
from dataclasses import dataclass
from typing import Any, Callable, Dict, Mapping, Optional

import grpc
from google.protobuf import json_format, struct_pb2

from . import credentials
from . import pipemesh_pb2 as pb
from . import pipemesh_pb2_grpc as rpc

Capability = Callable[[Mapping[str, Any]], Any]

# How long to wait before reconnecting, doubling up to the cap. The common first
# failure is a deployment where the runtime is a few seconds behind this process;
# the common later one is the runtime restarting. Both look the same from here.
_RETRY_MIN_SECONDS = 0.5
_RETRY_MAX_SECONDS = 30.0


@dataclass(frozen=True)
class CapabilityFailure(Exception):
    """Raise this to fail a call with a code the workflow can branch on.

    ``retryable`` is false by default: a business rule that said no does not say
    anything different when asked twice. Transport trouble is a different thing
    and the runtime classifies that itself.
    """

    code: str
    message: str = ""
    retryable: bool = False


class PipeMeshWorker:
    """Hosts capabilities and answers the runtime's invocations.

    ::

        worker = PipeMeshWorker("localhost:8080", organization="acme")

        @worker.capability("calculate_discount")
        def calculate_discount(customer):
            return {"rate": 0.20 if customer["tier"] == "gold" else 0.05}

        worker.run()
    """

    def __init__(
        self,
        target: str = "localhost:8080",
        *,
        organization: str = "default",
        api_key: Optional[str] = None,
        concurrency: int = 8,
        channel: Optional[grpc.Channel] = None,
    ) -> None:
        # A worker is a caller too. Its registration is bound to an organization
        # (§14), so a deployment that authenticates has to be able to tell which
        # one is connecting — an authenticated client with an anonymous worker
        # would be half a boundary.
        key = credentials.resolve(api_key)

        self._organization = organization
        self._owns_channel = channel is None
        self._channel = grpc.intercept_channel(
            channel or credentials.channel_for(target, key), *credentials.interceptors(key))
        self._stub = rpc.CapabilityWorkerStub(self._channel)
        self._capabilities: Dict[str, Capability] = {}

        # One queue feeding one request iterator: the stream has a single writer,
        # which is what gRPC requires and what several invocations answering at
        # once would otherwise break.
        self._outbound: "queue.Queue[Optional[pb.WorkerMessage]]" = queue.Queue()

        # Invocations run off the reading thread, so one slow capability does not
        # stop this worker from accepting the others.
        self._work = futures.ThreadPoolExecutor(max_workers=concurrency)
        # Set when someone asks this worker to stop — which is also how the
        # retry loop sleeps without ignoring a shutdown.
        self._stopped = threading.Event()

    def capability(self, name: str) -> Callable[[Capability], Capability]:
        """Register a function under the name a capability registration uses."""

        def register(function: Capability) -> Capability:
            self._capabilities[name] = function
            return function

        return register

    def run(self) -> None:
        """Connect and serve until stopped. Blocks.

        Reconnects on its own. A runtime that restarted, a proxy that closed an
        idle stream, and this process simply having started first are the same
        event from here: the connection went away and there are still
        capabilities to serve. Stopping at the first failure would leave the
        application up and quietly unable to do any work — an execution then
        waits at its capability step for a worker that is never coming back.
        """
        self._stopped.clear()
        delay = _RETRY_MIN_SECONDS

        while not self._stopped.is_set():
            connected_at = time.monotonic()
            try:
                self._serve()
            except grpc.RpcError as failure:
                # Stopping closes the stream, and the cancellation that follows is
                # the expected end of a shutdown rather than something to retry.
                if self._stopped.is_set():
                    return
                print(f"pipemesh: worker connection lost ({failure.code().name}); "
                      f"reconnecting in {delay:.1f}s", file=sys.stderr)

            if self._stopped.is_set():
                return

            # A connection that lasted is proof the runtime is reachable, so the
            # next failure starts over rather than inheriting a long wait.
            if time.monotonic() - connected_at > _RETRY_MAX_SECONDS:
                delay = _RETRY_MIN_SECONDS

            self._stopped.wait(delay)
            delay = min(delay * 2, _RETRY_MAX_SECONDS)

    def _serve(self) -> None:
        """One connection: register, then answer what arrives until it ends."""
        # A queue per connection. Results still waiting when a stream died belong
        # to invocations the runtime has already written off, so delivering them
        # over the next connection would be answering a question nobody asked.
        outbound: "queue.Queue[Optional[pb.WorkerMessage]]" = queue.Queue()
        self._outbound = outbound
        outbound.put(pb.WorkerMessage(
            registration=pb.WorkerRegistration(
                organization_id=self._organization,
                capability_ids=list(self._capabilities),
            )))

        for invocation in self._stub.Connect(self._requests(outbound)):
            self._work.submit(self._answer, invocation, outbound)

    def start(self) -> threading.Thread:
        """Serve on a background thread, for applications with their own loop."""
        thread = threading.Thread(target=self.run, name="pipemesh-worker", daemon=True)
        thread.start()
        return thread

    def stop(self) -> None:
        self._stopped.set()
        self._outbound.put(None)
        self._work.shutdown(wait=False)
        if self._owns_channel:
            self._channel.close()

    def __enter__(self) -> "PipeMeshWorker":
        return self

    def __exit__(self, *_: Any) -> None:
        self.stop()

    def _requests(self, outbound):
        while True:
            message = outbound.get()
            if message is None:
                return
            yield message

    def _answer(self, invocation: Any, outbound) -> None:
        result = pb.CapabilityResult(invocation_id=invocation.invocation_id)

        function = self._capabilities.get(invocation.capability_id)
        if function is None:
            result.failure.CopyFrom(pb.CapabilityFailure(
                code="worker.unknown_capability",
                message=f"this worker does not serve '{invocation.capability_id}'",
                retryable=False,
            ))
            outbound.put(pb.WorkerMessage(result=result))
            return

        try:
            answer = function(json_format.MessageToDict(invocation.input))
            result.output.CopyFrom(_to_struct(answer))
        except CapabilityFailure as declared:
            result.failure.CopyFrom(pb.CapabilityFailure(
                code=declared.code, message=declared.message, retryable=declared.retryable))
        except Exception as unexpected:  # noqa: BLE001
            # An exception the capability did not plan for is still an answer the
            # runtime needs. Letting it escape would leave the execution waiting
            # for a reply that is never coming.
            result.failure.CopyFrom(pb.CapabilityFailure(
                code="worker.raised",
                message=f"{type(unexpected).__name__}: {unexpected}",
                retryable=False,
            ))
            traceback.print_exc()

        outbound.put(pb.WorkerMessage(result=result))


def _to_struct(value: Any) -> struct_pb2.Struct:
    struct = struct_pb2.Struct()
    if isinstance(value, Mapping):
        struct.update(dict(value))
    elif value is not None:
        # A capability that returned a bare value still has to arrive as a named
        # field, because a Struct has no shape without one.
        struct.update({"value": value})
    return struct
