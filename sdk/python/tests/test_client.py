"""What a Python application sees when it talks to a PipeMesh runtime."""

from __future__ import annotations

import pytest

from pipemesh import ExecutionStatus, PipeMesh, PipeMeshError


@pytest.fixture
def mesh(runtime_address: str):
    with PipeMesh(runtime_address, organization="acme") as client:
        yield client


def _expensive(mesh: PipeMesh):
    return mesh.execute("venue_booking", {"price": 250})


def test_runs_a_workflow_and_stops_at_the_approval(mesh: PipeMesh):
    handle = _expensive(mesh)

    assert handle.status is ExecutionStatus.WAITING
    assert handle.status.is_waiting
    assert handle.current_step == "approval"
    assert handle.execution_id


def test_takes_the_other_branch_without_asking_anyone(mesh: PipeMesh):
    handle = mesh.execute("venue_booking", {"price": 10})

    assert handle.status is ExecutionStatus.COMPLETED
    assert handle.status.is_terminal


def test_reads_back_the_variables_it_sent(mesh: PipeMesh):
    handle = _expensive(mesh)

    snapshot = mesh.get(handle.execution_id)

    assert snapshot.organization == "acme"
    assert snapshot.workflow_id == "venue_booking"
    assert snapshot.variables["input"]["price"] == 250


def test_an_approval_finishes_the_execution(mesh: PipeMesh):
    waiting = _expensive(mesh)

    finished = mesh.approve(
        waiting.execution_id, f"{waiting.execution_id}:approval", decided_by="burak")

    assert finished.status is ExecutionStatus.COMPLETED
    assert finished.current_step == "booked"


def test_a_rejection_cancels_it(mesh: PipeMesh):
    waiting = _expensive(mesh)

    finished = mesh.reject(waiting.execution_id, f"{waiting.execution_id}:approval")

    assert finished.status is ExecutionStatus.CANCELLED


def test_the_same_decision_twice_changes_nothing(mesh: PipeMesh):
    waiting = _expensive(mesh)
    approval_id = f"{waiting.execution_id}:approval"

    first = mesh.approve(waiting.execution_id, approval_id)
    second = mesh.approve(waiting.execution_id, approval_id)

    assert first.status is second.status is ExecutionStatus.COMPLETED


def test_watching_starts_from_where_the_execution_already_is(mesh: PipeMesh):
    waiting = _expensive(mesh)

    first = next(iter(mesh.watch(waiting.execution_id)))

    assert first.kind == "started"
    assert first.sequence == 0
    assert first.status is ExecutionStatus.WAITING


def test_watching_ends_when_the_execution_does(mesh: PipeMesh):
    waiting = _expensive(mesh)
    updates = mesh.watch(waiting.execution_id)

    mesh.approve(waiting.execution_id, f"{waiting.execution_id}:approval")

    kinds = [update.kind for update in updates]

    assert kinds[0] == "started"
    assert kinds[-1] == "finished"
    assert "step_finished" in kinds


def test_says_which_execution_is_missing(mesh: PipeMesh):
    with pytest.raises(PipeMeshError) as failure:
        mesh.get("no-such-execution")

    assert failure.value.not_found


def test_says_which_workflow_is_missing(mesh: PipeMesh):
    with pytest.raises(PipeMeshError) as failure:
        mesh.execute("no_such_workflow")

    assert failure.value.not_found


def test_process_reads_a_message_and_runs_what_it_asked_for(mesh: PipeMesh):
    handle = mesh.process("please book a venue for Friday", {"price": 250})

    assert handle.status is ExecutionStatus.WAITING
    assert handle.current_step == "approval"


def test_process_records_which_intent_started_it(mesh: PipeMesh):
    handle = mesh.process("book a venue", {"price": 250})

    intent = mesh.get(handle.execution_id).variables["intent"]

    assert intent["id"] == "book_venue"
    assert intent["resolvedBy"] == "deterministic"


def test_process_says_when_it_could_not_tell_what_was_meant(mesh: PipeMesh):
    with pytest.raises(PipeMeshError) as failure:
        mesh.process("what is the weather like in Antalya")

    assert failure.value.failed_precondition
