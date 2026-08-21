import { useEffect, useState } from "react";

import { api, ConsoleError } from "../api";

type State = { done: false } | { done: true; failure: string | null };

/**
 * Where the emailed link lands.
 *
 * The link carries the token in the URL but the call that spends it is a POST,
 * so a mail client fetching the page for a preview cannot use it up.
 */
export function Verify() {
  const [state, setState] = useState<State>({ done: false });

  useEffect(() => {
    const token = new URLSearchParams(window.location.hash.split("?")[1] ?? "").get("token");
    if (!token) {
      setState({ done: true, failure: "This link is missing its token." });
      return;
    }

    api
      .verify(token)
      .then(() => setState({ done: true, failure: null }))
      .catch((error) =>
        setState({
          done: true,
          failure: error instanceof ConsoleError ? error.message : "Something went wrong.",
        }),
      );
  }, []);

  if (!state.done) return <main className="page"><p>Checking your link…</p></main>;

  return (
    <main className="page">
      <h1>{state.failure ? "That link did not work" : "You are all set"}</h1>
      <p>{state.failure ?? "Your address is confirmed."}</p>
      <p>
        <a href="#/signin">Sign in</a>
      </p>
    </main>
  );
}
