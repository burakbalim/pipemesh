#!/usr/bin/env bash
#
# Publishes one version: checks, tag, images.
#
# Everything it checks is also checked by `mvn test`, deliberately — a guard that
# only runs here is a guard that stops running the day somebody publishes by
# hand. This script exists to do the irreversible parts in the right order, not
# to be the only place the rules live.
set -euo pipefail

cd "$(dirname "$0")/.."
VERSION="$(tr -d '[:space:]' < VERSION)"
TAG="v${VERSION}"

echo "Releasing ${VERSION}"

# A published version never means two things. Refusing here is the same decision
# workflow versions already make (§24.1): immutable, or the number is a label
# rather than an identity.
if git rev-parse "${TAG}" >/dev/null 2>&1; then
    echo "error: ${TAG} already exists. Bump VERSION; a released version is not rewritten." >&2
    exit 1
fi

if [ -n "$(git status --porcelain)" ]; then
    echo "error: working tree is dirty. A tag should name something reproducible." >&2
    exit 1
fi

# Version agreement, stub freshness and proto compatibility all live in tests.
mvn -q test

echo "Building images"
docker build -f pipemesh-runtime/Dockerfile -t "pipemesh/runtime:${VERSION}" .
docker build -f pipemesh-console/Dockerfile -t "pipemesh/console:${VERSION}" .

# X.Y as well as X.Y.Z, so a deployment can follow patches without following
# minors. No `latest`: an install that cannot say what it runs cannot report a
# problem, and for an on-premise customer it is a dependency that changes by
# itself.
MINOR="${VERSION%.*}"
docker tag "pipemesh/runtime:${VERSION}" "pipemesh/runtime:${MINOR}"
docker tag "pipemesh/console:${VERSION}" "pipemesh/console:${MINOR}"

# Only now, when everything above passed: this is the copy the compatibility
# check compares against, so refreshing it earlier would make the check pass
# against itself and verify nothing.
cp proto/pipemesh.proto release/proto/pipemesh.proto
git add release/proto/pipemesh.proto
git commit -q -m "Release ${VERSION}" || true
git tag -a "${TAG}" -m "PipeMesh ${VERSION}"

echo
echo "Tagged ${TAG}. Still to push, once you are satisfied:"
echo "  git push && git push origin ${TAG}"
echo "  docker push pipemesh/runtime:${VERSION} && docker push pipemesh/runtime:${MINOR}"
echo "  docker push pipemesh/console:${VERSION} && docker push pipemesh/console:${MINOR}"
echo "  (cd sdk/python && python -m build && twine upload dist/*)"
echo "  (cd sdk/typescript && npm publish)"
