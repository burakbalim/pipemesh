import { useEffect, useState } from "react";

import { ConsoleError, UsageView, api } from "../api";

/** Money is integer micros on the wire, and only ever divided for display. */
function money(micros: number): string {
  return `$${(micros / 1_000_000).toFixed(2)}`;
}

function Meter({ label, used, limit }: { label: string; used: string; limit: string | null }) {
  return (
    <li>
      <span className="muted">{label}</span>
      <strong>
        {used}
        {limit && <span className="muted"> of {limit}</span>}
      </strong>
    </li>
  );
}

/**
 * What this plan allows and what has been used against it.
 *
 * A limit of zero means no limit, so it is shown as "unlimited" rather than as
 * a number nobody would believe.
 */
export function Usage() {
  const [view, setView] = useState<UsageView | null>(null);
  const [failure, setFailure] = useState<string | null>(null);

  useEffect(() => {
    api
      .usage()
      .then(setView)
      .catch((error) =>
        setFailure(error instanceof ConsoleError ? error.message : "Something went wrong."),
      );
  }, []);

  if (failure) return <p className="failure">{failure}</p>;
  if (!view) return null;

  const { plan, used } = view;
  const until = new Date(used.periodEnd).toLocaleDateString();

  return (
    <section>
      <h2>
        {plan.name} plan <span className="muted">· resets {until}</span>
      </h2>
      <ul className="usage">
        <Meter
          label="Executions"
          used={String(used.executions)}
          limit={plan.maxExecutions === 0 ? null : String(plan.maxExecutions)}
        />
        <Meter
          label="Tokens"
          used={used.tokens.toLocaleString()}
          limit={plan.maxTokens === 0 ? null : plan.maxTokens.toLocaleString()}
        />
        <Meter
          label="Spend"
          used={money(used.costMicros)}
          limit={plan.maxCostMicros === 0 ? null : money(plan.maxCostMicros)}
        />
      </ul>
    </section>
  );
}
