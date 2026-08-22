"""Attaching an API key to every call.

The key travels in call metadata, not in a request body: a request that carries
its own answer to "who am I" has not been authenticated, it has been asked
politely (DESIGN.md §23).
"""

from __future__ import annotations

import os
import warnings
from typing import Optional

import grpc

ENVIRONMENT_VARIABLE = "PIPEMESH_API_KEY"


def resolve(api_key: Optional[str]) -> Optional[str]:
    """The key that was passed, or the one in the environment."""
    if api_key:
        return api_key
    from_environment = os.environ.get(ENVIRONMENT_VARIABLE, "").strip()
    return from_environment or None


def channel_for(target: str, api_key: Optional[str]) -> grpc.Channel:
    """A plaintext channel, warning when a key would travel over it in the clear.

    Not refused: plaintext is right on a laptop, and refusing would make every
    development setup stand up TLS first. But a key sent unencrypted to a real
    deployment is a leaked key, and silence is how that happens.
    """
    if api_key:
        warnings.warn(
            "Sending an API key over a plaintext connection. That is fine locally and a "
            "leak anywhere else; use a secure channel against a real deployment.",
            stacklevel=3,
        )
    return grpc.insecure_channel(target)


def interceptors(api_key: Optional[str]):
    """Adds `authorization: Bearer <key>` to every call on the channel."""
    if not api_key:
        return ()
    header = ("authorization", f"Bearer {api_key}")
    return (
        _Unary(header),
        _UnaryStream(header),
        _StreamStream(header),
    )


class _Attaches:
    """Puts the header on a call, leaving whatever else was there alone."""

    def __init__(self, header) -> None:
        self._header = header

    def _with_header(self, client_call_details):
        metadata = list(client_call_details.metadata or [])
        metadata.append(self._header)
        return client_call_details._replace(metadata=metadata)


class _Unary(_Attaches, grpc.UnaryUnaryClientInterceptor):

    def intercept_unary_unary(self, continuation, client_call_details, request):
        return continuation(self._with_header(client_call_details), request)


class _UnaryStream(_Attaches, grpc.UnaryStreamClientInterceptor):

    def intercept_unary_stream(self, continuation, client_call_details, request):
        return continuation(self._with_header(client_call_details), request)


class _StreamStream(_Attaches, grpc.StreamStreamClientInterceptor):

    def intercept_stream_stream(self, continuation, client_call_details, request_iterator):
        return continuation(self._with_header(client_call_details), request_iterator)
