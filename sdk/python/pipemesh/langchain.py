"""Serves LangChain runnables as PipeMesh capabilities.

LangChain is reached, not depended on (DESIGN.md §35). Nothing here imports it:
the adapter accepts anything with an ``invoke()`` method, which both LangChain's
``Runnable`` and its ``BaseTool`` have. The cost of that is stated rather than
hidden — if LangChain ever changes the ``invoke`` contract, this finds out at run
time rather than at import time — and the benefit is that the sentence in §35
stays literally true, adapter included.

There is no new protocol here either. A chain reaches the runtime through the
same worker connection any other application code uses (§26.1), so a workflow
calls it as a plain capability and never learns what is behind it (§9.8)::

    from pipemesh import PipeMeshWorker
    from pipemesh.langchain import serve

    worker = PipeMeshWorker("localhost:8080", organization="acme")
    serve(worker, "summarize", summarize_chain)
    worker.run()

.. code-block:: json

    {"type": "capability", "capability": "summarize",
     "input": "$.article", "output": "summary"}
"""

from __future__ import annotations

from typing import Any, Callable, Mapping, Optional

from .worker import CapabilityFailure, PipeMeshWorker

Capability = Callable[[Mapping[str, Any]], Any]


def capability(runnable: Any, *, field: Optional[str] = None) -> Capability:
    """Wraps a runnable as a function the worker can serve.

    Use this when the function is wanted on its own — to compose it, to test it,
    or to register it by hand. :func:`serve` is the usual way in.

    :param field: hand the chain one field of the input instead of the whole
        object. A capability always receives an object while a LangChain chain
        usually takes one thing, and this is where that gap is closed — by
        naming the field, never by guessing which one was meant.
    """
    invoke = getattr(runnable, "invoke", None)
    if not callable(invoke):
        raise TypeError(
            f"{type(runnable).__name__} has no invoke() method; a LangChain runnable"
            " or tool does, and this adapter deliberately checks for the shape rather"
            " than importing langchain")

    def call(payload: Mapping[str, Any]) -> Any:
        try:
            return _readable(invoke(_argument(payload, field)))
        except CapabilityFailure:
            # Already an answer the workflow can branch on; nothing to add.
            raise
        except Exception as failure:  # noqa: BLE001
            # Not retryable by default, for the reason the worker gives: a chain
            # that refused on a business rule refuses the same way twice.
            raise CapabilityFailure(
                code="langchain.failed",
                message=f"{type(failure).__name__}: {failure}",
                retryable=False,
            ) from failure

    return call


def serve(
    worker: PipeMeshWorker,
    name: Optional[str] = None,
    runnable: Any = None,
    *,
    field: Optional[str] = None,
) -> Capability:
    """Registers a runnable on a worker under a capability name.

    The name may be left out when the object carries one — LangChain tools do::

        serve(worker, runnable=search_tool)          # uses search_tool.name
        serve(worker, "web_search", search_tool)     # or say it plainly
    """
    if runnable is None:
        raise TypeError("serve() needs a runnable to serve")

    resolved = name or getattr(runnable, "name", None)
    if not resolved:
        raise TypeError(
            "this runnable has no name of its own, so the capability name has to be"
            " given: serve(worker, 'summarize', chain)")

    function = capability(runnable, field=field)
    worker.capability(resolved)(function)
    return function


def _argument(payload: Mapping[str, Any], field: Optional[str]) -> Any:
    """What to hand the chain: the whole object, or the field that was named.

    An earlier version guessed — a one-field object meant its value — and the
    first realistic workflow broke on it, because {"tier": "gold"} is a whole
    input that happens to have one field. Guessing what a caller meant is the
    thing this codebase refuses everywhere else; it is refused here too.
    """
    if field is None:
        return payload
    if field not in payload:
        raise CapabilityFailure(
            code="langchain.missing_field",
            message=f"the input has no '{field}' to hand to the chain",
            retryable=False)
    return payload[field]


def _readable(answer: Any) -> Any:
    """Turns a LangChain answer into something a workflow variable can hold.

    A chain may return a mapping, a message object, a pydantic model or a plain
    string. Only the first is already what the wire wants; the rest would arrive
    as an empty object, which is the answer being lost rather than reported.
    """
    if isinstance(answer, Mapping):
        return answer

    content = getattr(answer, "content", None)
    if content is not None:
        return {"content": content}

    dump = getattr(answer, "model_dump", None)
    if callable(dump):
        return dump()

    # A bare value is left alone; the worker names it, rather than this guessing
    # at a field name a workflow did not ask for.
    return answer
