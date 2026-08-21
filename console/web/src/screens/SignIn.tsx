import { FormEvent, useState } from "react";

import { api, ConsoleError, User } from "../api";

export function SignIn({ onSignedIn }: { onSignedIn: (user: User) => void }) {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [failure, setFailure] = useState<string | null>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setFailure(null);
    try {
      onSignedIn(await api.signIn(email, password));
      window.location.hash = "#/";
    } catch (error) {
      // The server already decided how much to say: one message for a wrong
      // address or password, a different one for an unverified account.
      setFailure(error instanceof ConsoleError ? error.message : "Something went wrong.");
    }
  }

  return (
    <main className="page">
      <h1>Sign in</h1>
      <form onSubmit={submit}>
        <label>
          Email
          <input
            type="email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            required
          />
        </label>
        <label>
          Password
          <input
            type="password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            required
          />
        </label>
        {failure && <p className="failure">{failure}</p>}
        <button type="submit">Sign in</button>
      </form>
      <p>
        No account yet? <a href="#/signup">Create one</a>
      </p>
    </main>
  );
}
