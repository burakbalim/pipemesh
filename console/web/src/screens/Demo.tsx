import { useRef, useState } from "react";

/** One line of what happened, as it happened. */
interface Step {
  id: number;
  kind: string;
  detail: string;
}

/**
 * Runs the example workflow and shows it happening.
 *
 * Server-sent events rather than gRPC, which browsers cannot speak. The console
 * re-publishes what the runtime streams it, so this page talks to one thing that
 * already knows who it is.
 */
export function Demo() {
  const [running, setRunning] = useState(false);
  const [steps, setSteps] = useState<Step[]>([]);
  const [failure, setFailure] = useState<string | null>(null);
  const nextId = useRef(0);

  function record(kind: string, detail: string) {
    setSteps((seen) => [...seen, { id: nextId.current++, kind, detail }]);
  }

  async function run(mood: string) {
    setRunning(true);
    setSteps([]);
    setFailure(null);

    // fetch rather than EventSource: EventSource cannot POST, and the input the
    // demo runs on belongs in a body rather than in a query string.
    const response = await fetch("/api/v1/demo/executions", {
      method: "POST",
      credentials: "include",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ input: { mood } }),
    });

    if (!response.ok || !response.body) {
      setFailure("The demo could not be started.");
      setRunning(false);
      return;
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffered = "";

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffered += decoder.decode(value, { stream: true });
      // SSE separates events with a blank line; anything after the last one is a
      // half-arrived event and stays in the buffer.
      const events = buffered.split("\n\n");
      buffered = events.pop() ?? "";
      events.forEach((event) => show(event));
    }

    setRunning(false);
  }

  function show(event: string) {
    const kind = /^event:(.*)$/m.exec(event)?.[1]?.trim();
    const data = /^data:(.*)$/m.exec(event)?.[1]?.trim();
    if (!kind || !data) return;

    const detail = JSON.parse(data) as Record<string, unknown>;
    record(kind, describe(kind, detail));
  }

  function describe(kind: string, detail: Record<string, unknown>): string {
    if (kind === "STEP_STARTED") return `${detail.stepId} started`;
    if (kind === "STEP_FINISHED") return `${detail.stepId} finished in ${detail.latencyMs}ms`;
    if (kind === "FINISHED") return `Execution ${String(detail.status).toLowerCase()}`;
    if (kind === "STARTED") return "Watching";
    return kind.toLowerCase().replace("_", " ");
  }

  return (
    <section>
      <h2>Try it</h2>
      <p className="muted">
        Runs a small workflow on your own account, through the same API your code would use.
      </p>

      <div className="bar">
        <button type="button" disabled={running} onClick={() => run("good")}>
          Run with mood: good
        </button>
        <button type="button" disabled={running} onClick={() => run("bad")}>
          Run with mood: bad
        </button>
      </div>

      {failure && <p className="failure">{failure}</p>}

      <ol className="trace">
        {steps.map((step) => (
          <li key={step.id}>
            <span className="muted">{step.kind}</span>
            <span>{step.detail}</span>
          </li>
        ))}
      </ol>
    </section>
  );
}
