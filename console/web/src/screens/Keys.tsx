import { FormEvent, useEffect, useState } from "react";

import { ApiKey, ConsoleError, IssuedApiKey, api } from "../api";

/**
 * The keys an SDK authenticates with.
 *
 * A freshly issued secret is shown once and then gone, because the server keeps
 * only a hash. The screen says so rather than letting somebody discover it by
 * closing the page.
 */
export function Keys() {
  const [keys, setKeys] = useState<ApiKey[]>([]);
  const [name, setName] = useState("");
  const [issued, setIssued] = useState<IssuedApiKey | null>(null);
  const [failure, setFailure] = useState<string | null>(null);

  useEffect(() => {
    api.apiKeys().then(setKeys).catch(report);
  }, []);

  function report(error: unknown) {
    setFailure(error instanceof ConsoleError ? error.message : "Something went wrong.");
  }

  async function issue(event: FormEvent) {
    event.preventDefault();
    setFailure(null);
    try {
      const fresh = await api.issueApiKey(name);
      setIssued(fresh);
      setName("");
      setKeys(await api.apiKeys());
    } catch (error) {
      report(error);
    }
  }

  async function revoke(id: string) {
    setFailure(null);
    try {
      await api.revokeApiKey(id);
      setKeys(await api.apiKeys());
    } catch (error) {
      report(error);
    }
  }

  return (
    <section>
      <h2>API keys</h2>

      <form onSubmit={issue}>
        <label>
          Name this key
          <input
            value={name}
            onChange={(event) => setName(event.target.value)}
            placeholder="laptop"
            required
          />
        </label>
        <button type="submit">Create key</button>
      </form>

      {issued && (
        <div className="notice">
          <p>
            <strong>Copy this now.</strong> It is not stored and will not be shown again.
          </p>
          <code>{issued.secret}</code>
        </div>
      )}

      {failure && <p className="failure">{failure}</p>}

      <ul className="keys">
        {keys.map((key) => (
          <li key={key.id}>
            <span>
              <strong>{key.name}</strong> <code>{key.prefix}…</code>
            </span>
            <span className="muted">
              {key.lastUsedAt ? `last used ${new Date(key.lastUsedAt).toLocaleDateString()}` : "never used"}
            </span>
            <button type="button" onClick={() => revoke(key.id)}>
              Revoke
            </button>
          </li>
        ))}
        {keys.length === 0 && <li className="muted">No keys yet.</li>}
      </ul>
    </section>
  );
}
