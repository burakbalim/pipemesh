import { api, User } from "../api";
import { Keys } from "./Keys";

/**
 * What is here now is only the frame: who is signed in, and a way out.
 *
 * Usage against quota, API keys and the demo arrive with their own slices; this
 * screen is the foundation they hang off, and it deliberately does not pretend
 * to show numbers that nothing is counting yet.
 */
export function Dashboard({ user, onSignedOut }: { user: User; onSignedOut: () => void }) {
  async function signOut() {
    await api.signOut();
    onSignedOut();
  }

  return (
    <main className="page">
      <header className="bar">
        <h1>PipeMesh</h1>
        <button type="button" onClick={signOut}>
          Sign out
        </button>
      </header>
      <p>
        Signed in as <strong>{user.email}</strong>.
      </p>
      <Keys />
    </main>
  );
}
