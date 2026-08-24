"""The files behind the demo, as the demo reads them.

Not a copy pasted into a page: these are the files the running system loaded, so
a page showing something different from what is running is a bug rather than a
stale doc. The list is fixed here — there is no path parameter, and nothing
outside it can be asked for.
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(os.environ.get("DEMO_SOURCE_ROOT", Path(__file__).resolve().parents[2]))
EXAMPLE = ROOT / "examples" / "vendor-selection"


@dataclass(frozen=True)
class Group:
    """Files that answer one question, with the question as the title."""

    title: str
    note: str
    paths: list[Path]


GROUPS = [
    Group(
        "What a message is read as",
        "Phrases first, a model only when they do not settle it. Reading a message picks a "
        "workflow and stops there — never a step to start at, never a branch to take. A "
        "greeting matches nothing, so nothing runs.",
        [EXAMPLE / "intents" / "intents.json",
         EXAMPLE / "prompts" / "intent" / "classify.v1.md"],
    ),
    Group(
        "The flow",
        "Declarative. Moving the approval threshold from ten thousand to fifty "
        "thousand is editing this file — no deployment, no code.",
        [EXAMPLE / "workflows" / "vendor-selection.json"],
    ),
    Group(
        "What the flow may call",
        "The workflow names a capability. Where it runs, who owns it and whether "
        "it can be repeated after a crash are declared here, not there.",
        sorted((EXAMPLE / "capabilities").glob("*.json")),
    ),
    Group(
        "What the model is asked",
        "Versioned files, not strings in code. Changing a prompt is a change "
        "somebody can review.",
        sorted((EXAMPLE / "prompts" / "procurement").glob("*.md")),
    ),
    Group(
        "What the model must return",
        "Validated at the step boundary, so a malformed answer fails there "
        "rather than three steps later as something strange.",
        sorted((EXAMPLE / "schemas").glob("*.json")),
    ),
    Group(
        "The company's own code",
        "Ordinary Python, reached as capabilities. It contains no workflow, and "
        "the workflow contains none of it.",
        [EXAMPLE / "procurement.py"],
    ),
    Group(
        "This demo",
        "The application around the runtime: sessions, an approver's inbox, and "
        "the pages you are looking at.",
        sorted((ROOT / "demo" / "app").glob("*.py")),
    ),
]


def bundle() -> list[dict]:
    """Every listed file, read now."""
    return [
        {
            "title": group.title,
            "note": group.note,
            "files": [
                {
                    "path": str(path.relative_to(ROOT)),
                    "language": LANGUAGES.get(path.suffix, "text"),
                    "body": read(path),
                }
                for path in group.paths
            ],
        }
        for group in GROUPS
    ]


LANGUAGES = {".json": "json", ".py": "python", ".md": "markdown", ".yaml": "yaml"}


def read(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError as missing:
        # A file the image did not ship is worth saying out loud on the page;
        # silently showing an empty box would look like an empty file.
        return f"(could not be read: {missing})"
