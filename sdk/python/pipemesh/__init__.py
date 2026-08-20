"""PipeMesh — a declarative runtime for AI workflows.

This package talks to a running PipeMesh server. It does not execute workflows:
the runtime does that, and an SDK's job is to reach it (DESIGN.md §26.2).
"""

from .worker import CapabilityFailure, PipeMeshWorker
from .client import (
    Approval,
    ExecutionHandle,
    ExecutionSnapshot,
    ExecutionStatus,
    PipeMesh,
    PipeMeshError,
    Update,
)

__all__ = [
    "Approval",
    "CapabilityFailure",
    "PipeMeshWorker",
    "ExecutionHandle",
    "ExecutionSnapshot",
    "ExecutionStatus",
    "PipeMesh",
    "PipeMeshError",
    "Update",
]
