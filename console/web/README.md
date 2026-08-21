# PipeMesh Console — web

Four screens: create an account, confirm the address, sign in, and the dashboard the later
slices hang off.

```bash
npm install
npm run dev      # http://localhost:5173, proxying /api to the console on :8090
npm run build
```

## Two decisions worth knowing

**The session is an HttpOnly cookie.** Nothing here holds a token — the browser attaches it and
no script can read it. Every call therefore passes `credentials: "include"`; forgetting it is
the reason a screen would look signed out for no visible reason.

**The browser never speaks gRPC.** Live execution updates will arrive as SSE that the console
re-publishes from the runtime's stream. Browsers cannot speak gRPC server streaming, and putting
a proxy in front of the runtime would mean a second identity path for the same person.
