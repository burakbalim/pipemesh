"""A LangChain chain, reached by a workflow that has never heard of LangChain.

Nothing here imports langchain, and neither does the adapter — the objects below
are the shape it accepts, which is the whole point of accepting a shape
(DESIGN.md §35).
"""

from __future__ import annotations

import time

import pytest

from pipemesh import CapabilityFailure, ExecutionStatus, PipeMesh, PipeMeshWorker
from pipemesh.langchain import capability, serve


@pytest.fixture
def mesh(runtime_address: str):
    with PipeMesh(runtime_address, organization="acme") as client:
        yield client


class Chain:
    """What a LangChain Runnable looks like from outside: it has invoke()."""

    def __init__(self, answer):
        self.answer = answer

    def invoke(self, value):
        return self.answer(value) if callable(self.answer) else self.answer


class Tool(Chain):
    """A LangChain tool additionally carries its own name and description."""

    name = "calculate_discount"
    description = "Tier-based discount"


class Message:
    """The shape of an AIMessage, which is not a mapping."""

    def __init__(self, content):
        self.content = content


def _serving(runtime_address: str, runnable, name=None) -> PipeMeshWorker:
    worker = PipeMeshWorker(runtime_address, organization="acme")
    serve(worker, name, runnable)
    worker.start()
    # The runtime retries the step, so a late worker is fine; waiting keeps the
    # test about what it is testing.
    time.sleep(0.3)
    return worker


def test_a_workflow_calls_a_chain_without_knowing_it_is_one(
        runtime_address: str, mesh: PipeMesh):
    chain = Chain(lambda customer: {"rate": 0.2 if customer["tier"] == "gold" else 0.05})

    worker = _serving(runtime_address, chain, "calculate_discount")
    try:
        handle = mesh.execute("discount_check", {"tier": "gold"})

        assert handle.status is ExecutionStatus.COMPLETED
        assert mesh.get(handle.execution_id).variables["discount"]["rate"] == 0.2
    finally:
        worker.stop()


def test_a_tool_brings_its_own_name(runtime_address: str, mesh: PipeMesh):
    worker = _serving(runtime_address, Tool(lambda _: {"rate": 0.5}))
    try:
        handle = mesh.execute("discount_check", {"tier": "gold"})

        assert mesh.get(handle.execution_id).variables["discount"]["rate"] == 0.5
    finally:
        worker.stop()


def test_a_failing_chain_becomes_something_the_workflow_can_branch_on(
        runtime_address: str, mesh: PipeMesh):
    def explode(_):
        raise ValueError("the chain gave up")

    worker = _serving(runtime_address, Chain(explode), "calculate_discount")
    try:
        handle = mesh.execute("discount_check", {"tier": "gold"})

        assert handle.status is ExecutionStatus.FAILED
    finally:
        worker.stop()


def test_the_chain_gets_the_whole_input_unless_a_field_is_named():
    seen = []
    function = capability(Chain(lambda value: seen.append(value) or {"ok": True}))

    function({"tier": "gold"})

    assert seen == [{"tier": "gold"}], "a one-field object is still the whole input"


def test_a_named_field_is_handed_over_on_its_own():
    seen = []
    function = capability(
        Chain(lambda value: seen.append(value) or {"ok": True}), field="article")

    function({"article": "birds", "style": "short"})

    assert seen == ["birds"]


def test_a_named_field_that_is_not_there_is_reported_not_guessed():
    function = capability(Chain({"ok": True}), field="article")

    with pytest.raises(CapabilityFailure) as failure:
        function({"style": "short"})

    assert failure.value.code == "langchain.missing_field"


def test_a_message_answer_is_kept_rather_than_lost():
    function = capability(Chain(Message("a summary")))

    assert function({"article": "birds"}) == {"content": "a summary"}


def test_a_plain_string_answer_is_left_for_the_worker_to_name():
    function = capability(Chain("a summary"))

    assert function({"article": "birds"}) == "a summary"


def test_a_declared_failure_passes_through_unchanged():
    def refuse(_):
        raise CapabilityFailure(code="policy.refused", message="not allowed")

    function = capability(Chain(refuse))

    with pytest.raises(CapabilityFailure) as failure:
        function({"article": "birds"})

    assert failure.value.code == "policy.refused", "the chain's own answer, not a wrapper"


def test_something_that_is_not_a_runnable_is_refused_before_the_worker_runs():
    with pytest.raises(TypeError) as refused:
        capability(object())

    assert "invoke()" in str(refused.value)


def test_a_nameless_runnable_must_be_given_a_name():
    worker = PipeMeshWorker("localhost:1", organization="acme")

    with pytest.raises(TypeError) as refused:
        serve(worker, None, Chain({"ok": True}))

    assert "capability name" in str(refused.value)


def test_the_adapter_does_not_import_langchain():
    import pipemesh.langchain as adapter

    source = __import__("inspect").getsource(adapter)

    assert "import langchain" not in source
    assert "from langchain" not in source
