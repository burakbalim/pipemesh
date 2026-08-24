"""HTTP for the demo: routes, and nothing else.

Every decision about what a request means lives in `conversations.py`. This file
parses, delegates once, and shapes a reply.
"""

from __future__ import annotations

import json
import os
import secrets
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Iterator

from fastapi import Body, FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
from pipemesh import PipeMesh, PipeMeshError

import procurement

from . import sources
from .conversations import Conversations

RUNTIME = os.environ.get("PIPEMESH_TARGET", "localhost:8080")
ORGANIZATION = os.environ.get("PIPEMESH_ORGANIZATION", "demo")
STATIC = Path(__file__).parent / "static"
SESSION_COOKIE = "pm_session"

mesh = PipeMesh(RUNTIME, organization=ORGANIZATION)
conversations = Conversations(mesh)


@asynccontextmanager
async def lifespan(_: FastAPI):
    # The capabilities are served from this process, so it is both the demo's
    # front end and the company's worker — two roles, one deployment, which is
    # exactly how a small application would do it.
    worker = procurement.serve(RUNTIME, ORGANIZATION)
    worker.start()
    try:
        yield
    finally:
        worker.stop()
        mesh.close()


app = FastAPI(title="PipeMesh demo", lifespan=lifespan)


@app.middleware("http")
async def with_session(request: Request, call_next):
    """Give every visitor an id, and keep them apart by it.

    It identifies nobody — it is a random string that decides which requests are
    yours. There is no account here, and nothing to attach to a person.
    """
    session = request.cookies.get(SESSION_COOKIE) or secrets.token_hex(8)
    request.state.session = session

    response = await call_next(request)
    if request.cookies.get(SESSION_COOKIE) != session:
        response.set_cookie(SESSION_COOKIE, session, httponly=True,
                            samesite="lax", max_age=86400)
    return response


# -- pages ------------------------------------------------------------------

@app.get("/")
def customer() -> FileResponse:
    return FileResponse(STATIC / "customer.html")


@app.get("/approvals")
def approvals_page() -> FileResponse:
    return FileResponse(STATIC / "approvals.html")


@app.get("/source")
def source_page() -> FileResponse:
    return FileResponse(STATIC / "source.html")


# -- one request ------------------------------------------------------------

@app.post("/api/requests")
def start(request: Request, message: str = Body(embed=True)) -> dict:
    """Either an execution was started, or there is something to say back.

    Both are ordinary answers. A message the runtime could not place is not an
    error — the call was fine and nothing went wrong; it simply named no work.
    """
    if not message.strip():
        raise HTTPException(400, "say what you need")

    return dict(conversations.start(request.state.session, message.strip()))


@app.get("/api/requests")
def mine(request: Request) -> dict:
    """What this visitor started, so a reloaded page finds its place again."""
    return {"executionIds": conversations.executions_of(request.state.session)}


@app.get("/api/requests/{execution_id}")
def one(execution_id: str) -> dict:
    return _guarded(lambda: dict(conversations.snapshot(execution_id)))


@app.get("/api/requests/{execution_id}/events")
def events(execution_id: str, seen: int = 0) -> StreamingResponse:
    """Everything that happened to this execution, then everything that does."""
    trace = conversations.trace_of(execution_id)
    if trace is None:
        raise HTTPException(404, "this demo is not following that execution")

    return StreamingResponse(
        _sse(trace.follow(seen)),
        media_type="text/event-stream",
        # Without these a proxy will happily buffer the whole stream and deliver
        # it once the execution is over, which is the one thing a live view
        # cannot survive.
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@app.post("/api/requests/{execution_id}/choice")
def choose(execution_id: str, vendor_id: str = Body(embed=True, alias="vendorId")) -> dict:
    return _guarded(lambda: _accepted(conversations.choose(execution_id, vendor_id)))


# -- the approver's side ----------------------------------------------------

@app.get("/api/approvals")
def pending() -> dict:
    return {"waiting": conversations.awaiting_approval()}


@app.post("/api/approvals/{execution_id}")
def decide(execution_id: str, approved: bool = Body(embed=True),
           decided_by: str = Body(embed=True, alias="decidedBy")) -> dict:
    return _guarded(
        lambda: _accepted(conversations.decide(execution_id, approved, decided_by or "approver")))


# -- the rest ---------------------------------------------------------------

@app.get("/api/source")
def source() -> dict:
    return {"groups": sources.bundle()}


@app.get("/healthz")
def healthy() -> dict:
    return {"ok": True}


app.mount("/static", StaticFiles(directory=STATIC), name="static")


def _sse(events: Iterator) -> Iterator[str]:
    """Server-sent events, with a comment line standing in for silence."""
    for event in events:
        yield ": keep-alive\n\n" if event is None else f"data: {json.dumps(event)}\n\n"


def _guarded(call):
    """Turn the two failures a visitor can cause into the status that says so."""
    try:
        return call()
    except PipeMeshError as refused:
        raise HTTPException(404 if refused.not_found else 400, str(refused)) from None
    except LookupError as unknown:
        raise HTTPException(400, str(unknown)) from None


def _accepted(_: None) -> dict:
    """A decision is delivered to the runtime; what it does next is its own.

    Returning the execution's state here would be a guess — it moves on its own
    thread, and the page is already watching it.
    """
    return {"accepted": True}
