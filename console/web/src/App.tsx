import { useEffect, useState } from "react";

import { api, User } from "./api";
import { SignUp } from "./screens/SignUp";
import { SignIn } from "./screens/SignIn";
import { Verify } from "./screens/Verify";
import { Dashboard } from "./screens/Dashboard";

type Screen = "signup" | "signin" | "verify" | "dashboard";

/**
 * Which screen is showing.
 *
 * A hash rather than a router: four screens, no nesting, and a dependency that
 * would carry more concepts than the whole console has.
 */
function screenFromLocation(): Screen {
  const hash = window.location.hash.replace("#/", "");
  if (hash.startsWith("verify")) return "verify";
  if (hash === "signup") return "signup";
  if (hash === "signin") return "signin";
  return "dashboard";
}

export function App() {
  const [screen, setScreen] = useState<Screen>(screenFromLocation);
  const [user, setUser] = useState<User | null>(null);
  const [checking, setChecking] = useState(true);

  useEffect(() => {
    const onHashChange = () => setScreen(screenFromLocation());
    window.addEventListener("hashchange", onHashChange);
    return () => window.removeEventListener("hashchange", onHashChange);
  }, []);

  useEffect(() => {
    api.currentUser().then((signedIn) => {
      setUser(signedIn);
      setChecking(false);
    });
  }, [screen]);

  // Deciding before the answer arrives would flash the sign-in screen at
  // somebody who is already signed in.
  if (checking) return <main className="page" />;

  if (screen === "verify") return <Verify />;
  if (screen === "signup") return <SignUp />;
  if (!user) return <SignIn onSignedIn={setUser} />;

  return <Dashboard user={user} onSignedOut={() => setUser(null)} />;
}
