"""A key has to change the answer, or the test proves nothing.

The other suites run against a server that identifies nobody: sending a key
there is indistinguishable from not sending one. This one starts a server that
does identify callers, so the difference is visible.
"""

from __future__ import annotations

import pathlib
import subprocess
import sys
import time

import grpc
import pytest

from pipemesh import PipeMesh, PipeMeshError

KEY = "pm_the-only-valid-key"

REPO = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))


def _classpath() -> str:
    listing = REPO / "pipemesh-runtime" / "target" / "test-classpath.txt"
    if not listing.exists():
        pytest.skip("build the runtime test classpath first")
    return ":".join([
        str(REPO / "pipemesh-runtime" / "target" / "classes"),
        str(REPO / "pipemesh-runtime" / "target" / "test-classes"),
        listing.read_text().strip(),
    ])


@pytest.fixture(scope="module")
def authenticated_runtime():
    """The same test server, told to expect one key."""
    process = subprocess.Popen(
        ["java", "-cp", _classpath(), "io.pipemesh.runtime.TestRuntimeServer"],
        stdout=subprocess.PIPE, text=True,
        cwd=str(REPO / "sdk" / "python"),
        env={**dict(__import__("os").environ), "PIPEMESH_TEST_KEY": KEY},
    )
    port = process.stdout.readline().strip()
    try:
        yield f"localhost:{port}"
    finally:
        process.terminate()
        process.wait(timeout=10)


def test_a_valid_key_reaches_a_deployment_that_authenticates(authenticated_runtime):
    with PipeMesh(authenticated_runtime, api_key=KEY) as mesh:
        handle = mesh.execute("policy_check")

        # The organization came from the key, not from the request: the client
        # never named one.
        assert mesh.get(handle.execution_id).organization == "acme"


def test_without_a_key_a_permission_is_refused(authenticated_runtime):
    with PipeMesh(authenticated_runtime, organization="acme") as mesh:
        handle = mesh.execute("policy_check")

        with pytest.raises(PipeMeshError) as refused:
            next(iter(mesh.watch(handle.execution_id)))

    assert refused.value.code is grpc.StatusCode.PERMISSION_DENIED


def test_a_wrong_key_is_no_better_than_none(authenticated_runtime):
    with PipeMesh(authenticated_runtime, api_key="pm_not-it") as mesh:
        handle = mesh.execute("policy_check")

        with pytest.raises(PipeMeshError):
            next(iter(mesh.watch(handle.execution_id)))


def test_the_key_never_appears_in_an_error(authenticated_runtime):
    with PipeMesh(authenticated_runtime, api_key="pm_not-it") as mesh:
        try:
            mesh.get("no-such-execution")
        except PipeMeshError as failure:
            assert "pm_not-it" not in str(failure)
            assert "pm_not-it" not in repr(failure)
