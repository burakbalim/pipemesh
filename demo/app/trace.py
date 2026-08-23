"""What happened to one execution, kept where browsers can read it.

The runtime's own stream ends when the execution does, and it is served to
whoever asked at the time. A browser is not that: it arrives late, refreshes,
loses its connection on a phone going through a tunnel. So the demo keeps its
own log and lets connections come and go against it.

This is the ordinary shape for an application that watches a runtime, not a
demo shortcut. The one thing it must not become is a second source of truth:
nothing here is authoritative, and `mesh.get` is still the answer to "what is
actually going on".
"""

from __future__ import annotations

import threading
from typing import Any, Iterator, Mapping

# Long enough to keep a proxy from closing an idle stream, short enough that a
# browser that went away is noticed while the execution is still open.
HEARTBEAT_SECONDS = 15.0


class Trace:
    """An append-only list of events, with readers that wait for more."""

    def __init__(self) -> None:
        self._events: list[Mapping[str, Any]] = []
        self._closed = False
        self._arrived = threading.Condition()

    def append(self, event: Mapping[str, Any]) -> None:
        with self._arrived:
            self._events.append(event)
            self._arrived.notify_all()

    def close(self) -> None:
        with self._arrived:
            self._closed = True
            self._arrived.notify_all()

    @property
    def events(self) -> list[Mapping[str, Any]]:
        with self._arrived:
            return list(self._events)

    def follow(self, seen: int = 0) -> Iterator[Mapping[str, Any] | None]:
        """Yield events from index `seen` onwards, then wait for more.

        Yields `None` when nothing arrived for a while. A reader turns that into
        a keep-alive; without it a dropped browser would leave this thread
        parked on the condition until the execution ended.
        """
        while True:
            with self._arrived:
                while seen >= len(self._events) and not self._closed:
                    if not self._arrived.wait(HEARTBEAT_SECONDS):
                        break

                pending = self._events[seen:]
                closed = self._closed

            for event in pending:
                seen += 1
                yield event

            if not pending:
                if closed:
                    return
                yield None
