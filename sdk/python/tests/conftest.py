"""Starts a real PipeMesh runtime for the tests to call.

The server is the Java one, in its own process, reached over a real socket —
which is the only way an SDK test proves anything. A mocked stub would confirm
the client calls itself the way it was written.
"""

from __future__ import annotations

import pathlib
import subprocess
import sys
import time

import grpc
import pytest

REPO = pathlib.Path(__file__).resolve().parents[3]
CLASSPATH_FILE = REPO / "pipemesh-grpc" / "target" / "test-classpath.txt"
CLASSES = [
    REPO / "pipemesh-grpc" / "target" / "classes",
    REPO / "pipemesh-grpc" / "target" / "test-classes",
]

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))


def _classpath() -> str:
    if not CLASSPATH_FILE.exists():
        pytest.skip(
            "run `mvn -pl pipemesh-grpc dependency:build-classpath "
            "-Dmdep.outputFile=target/test-classpath.txt` first")
    parts = [str(path) for path in CLASSES] + [CLASSPATH_FILE.read_text().strip()]
    return ":".join(parts)


@pytest.fixture(scope="session")
def runtime_address() -> str:
    process = subprocess.Popen(
        ["java", "-cp", _classpath(), "io.pipemesh.grpc.TestRuntimeServer"],
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
    )
    try:
        port = process.stdout.readline().strip()
        if not port.isdigit():
            pytest.fail(f"the runtime did not report a port, said: {port!r}")

        address = f"localhost:{port}"
        _await_ready(address)
        yield address
    finally:
        process.terminate()
        process.wait(timeout=10)


def _await_ready(address: str, timeout: float = 10.0) -> None:
    deadline = time.time() + timeout
    with grpc.insecure_channel(address) as channel:
        while time.time() < deadline:
            try:
                grpc.channel_ready_future(channel).result(timeout=1)
                return
            except grpc.FutureTimeoutError:
                continue
    pytest.fail(f"the runtime at {address} never became ready")
