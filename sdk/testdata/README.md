# SDK test configuration

The workflows the Python and TypeScript test suites run against, as files rather than as strings
in a Java class.

They are files for one reason: the test server assembles itself the way the shipped runtime does
(`RuntimeAssembly`), so what the SDK tests exercise is the thing that gets deployed. A hand-wired
server would prove the SDKs work against a runtime nobody runs.
