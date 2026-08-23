# The demo as a process.
#
# An application that uses PipeMesh, not part of it: it ships the SDK, the
# example, and the web pages around them. Nothing here is a runtime.

FROM python:3.12-slim

# Not root, for the same reason the runtime image is not: an image that needs it
# fails somebody's security review, and by then it has been distributed.
RUN useradd --create-home --shell /usr/sbin/nologin demo
WORKDIR /app

# Dependencies first — they change far less often than the demo does, so this
# layer survives most rebuilds.
COPY demo/requirements.txt demo/requirements.txt
COPY sdk/python sdk/python
RUN pip install --no-cache-dir -r demo/requirements.txt ./sdk/python

COPY examples/vendor-selection examples/vendor-selection
COPY demo/app demo/app

USER demo
ENV PYTHONUNBUFFERED=1 \
    DEMO_SOURCE_ROOT=/app \
    PIPEMESH_TARGET=runtime:8080 \
    PIPEMESH_ORGANIZATION=demo
EXPOSE 8000

# One worker on purpose: this process serves the capabilities as well as the
# pages, and a second copy would register a second worker for the same three.
CMD ["uvicorn", "demo.app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
