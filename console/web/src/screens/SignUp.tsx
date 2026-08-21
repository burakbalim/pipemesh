import { FormEvent, useState } from "react";

import { api, ConsoleError } from "../api";

/** Opening an account. Ends by telling the person to go and read their mail. */
export function SignUp() {
  const [organizationName, setOrganizationName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [failure, setFailure] = useState<string | null>(null);
  const [sent, setSent] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setFailure(null);
    try {
      await api.register(organizationName, email, password);
      setSent(true);
    } catch (error) {
      setFailure(error instanceof ConsoleError ? error.message : "Something went wrong.");
    }
  }

  if (sent) {
    return (
      <main className="page">
        <h1>Check your email</h1>
        <p>
          We sent a link to <strong>{email}</strong>. Open it to finish setting up your account.
        </p>
      </main>
    );
  }

  return (
    <main className="page">
      <h1>Create an account</h1>
      <form onSubmit={submit}>
        <label>
          Organization
          <input
            value={organizationName}
            onChange={(event) => setOrganizationName(event.target.value)}
            required
          />
        </label>
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
            minLength={12}
            required
          />
        </label>
        {failure && <p className="failure">{failure}</p>}
        <button type="submit">Create account</button>
      </form>
      <p>
        Already have one? <a href="#/signin">Sign in</a>
      </p>
    </main>
  );
}
