"""Business code in this process, called by the runtime as a capability.

The workflow says only `"capability": "calculate_discount"`. That it happens to
be a Python function in a test process — rather than an MCP tool or a REST
endpoint — is something the workflow never learns (DESIGN.md §9.8).
"""

from __future__ import annotations

import time

import pytest

from pipemesh import CapabilityFailure, ExecutionStatus, PipeMesh, PipeMeshWorker


@pytest.fixture
def mesh(runtime_address: str):
    with PipeMesh(runtime_address, organization="acme") as client:
        yield client


def _worker(runtime_address: str, function, name: str = "calculate_discount") -> PipeMeshWorker:
    worker = PipeMeshWorker(runtime_address, organization="acme")
    worker.capability(name)(function)
    worker.start()
    _await_connected()
    return worker


def _await_connected() -> None:
    # The runtime retries the step, so a worker arriving a moment late is fine —
    # but waiting here keeps the tests about what they are testing.
    time.sleep(0.3)


def test_runs_a_capability_that_lives_in_this_process(runtime_address: str, mesh: PipeMesh):
    def calculate_discount(customer):
        return {"rate": 0.2 if customer["tier"] == "gold" else 0.05}

    worker = _worker(runtime_address, calculate_discount)
    try:
        handle = mesh.execute("discount_check", {"tier": "gold"})

        assert handle.status is ExecutionStatus.COMPLETED
        assert mesh.get(handle.execution_id).variables["discount"]["rate"] == 0.2
    finally:
        worker.stop()


def test_the_workflow_never_learns_where_the_code_lives(runtime_address: str, mesh: PipeMesh):
    seen = []

    def calculate_discount(customer):
        seen.append(customer)
        return {"rate": 0.05}

    worker = _worker(runtime_address, calculate_discount)
    try:
        mesh.execute("discount_check", {"tier": "silver"})

        assert seen == [{"tier": "silver"}], "the step's input arrived as the function's argument"
    finally:
        worker.stop()


def test_a_declared_failure_fails_the_step(runtime_address: str, mesh: PipeMesh):
    def calculate_discount(customer):
        raise CapabilityFailure("billing.no_such_tier", f"unknown tier {customer['tier']}")

    worker = _worker(runtime_address, calculate_discount)
    try:
        handle = mesh.execute("discount_check", {"tier": "platinum"})

        assert handle.status is ExecutionStatus.FAILED
    finally:
        worker.stop()


def test_an_unexpected_exception_still_answers(runtime_address: str, mesh: PipeMesh):
    def calculate_discount(customer):
        return customer["missing_field"]

    worker = _worker(runtime_address, calculate_discount)
    try:
        handle = mesh.execute("discount_check", {"tier": "gold"})

        assert handle.status is ExecutionStatus.FAILED, (
            "an execution must not wait forever for a reply that is never coming")
    finally:
        worker.stop()


def test_a_capability_this_worker_does_not_serve_is_reported(runtime_address: str, mesh: PipeMesh):
    worker = _worker(runtime_address, lambda customer: {}, name="something_else")
    try:
        handle = mesh.execute("discount_check", {"tier": "gold"})

        assert handle.status is ExecutionStatus.FAILED
    finally:
        worker.stop()


def test_with_no_worker_at_all_the_step_gives_up(mesh: PipeMesh):
    handle = mesh.execute("discount_check", {"tier": "gold"})

    assert handle.status is ExecutionStatus.FAILED, (
        "retried while no worker was connected, then stopped rather than waiting forever")
