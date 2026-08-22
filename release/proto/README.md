# The proto as it was last released

A copy, refreshed only by the release step — never during development.

That is the whole point. `ProtoCompatibilityTest` compares the working proto against this one, so
a field removed or renumbered fails the build. If this were refreshed on every change, the
comparison would always pass and would be checking nothing.

It lives here rather than under `proto/` because everything in that directory is compiled, and a
second copy of the same package is a duplicate definition rather than a reference point.
