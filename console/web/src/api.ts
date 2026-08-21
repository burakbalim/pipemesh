/**
 * Everything the screens know about the server.
 *
 * The session lives in an HttpOnly cookie, so nothing here holds a token: the
 * browser attaches it and no script can read it. `credentials: "include"` is
 * what makes that work, and forgetting it is the reason a screen would look
 * signed out for no visible reason.
 */

export interface ApiError {
  code: string;
  message: string;
}

export class ConsoleError extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly status: number,
  ) {
    super(message);
  }
}

export interface User {
  id: string;
  email: string;
  organizationId: string;
}

export interface Organization {
  id: string;
  name: string;
  planId: string;
}

async function call<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(`/api/v1${path}`, {
    ...init,
    credentials: "include",
    headers: { "Content-Type": "application/json", ...(init.headers ?? {}) },
  });

  if (response.ok) {
    return response.status === 204 || response.headers.get("content-length") === "0"
      ? (undefined as T)
      : ((await response.json().catch(() => undefined)) as T);
  }

  const failure = (await response.json().catch(() => undefined)) as ApiError | undefined;
  throw new ConsoleError(
    failure?.code ?? "unexpected",
    failure?.message ?? `The server answered ${response.status}.`,
    response.status,
  );
}

export interface ApiKey {
  id: string;
  name: string;
  prefix: string;
  createdAt: string;
  lastUsedAt: string | null;
}

export interface IssuedApiKey {
  key: ApiKey;
  /** The one moment this exists outside the holder's hands. */
  secret: string;
}

export interface Plan {
  id: string;
  name: string;
  /** Zero means no limit, the same convention a workflow budget uses. */
  maxExecutions: number;
  maxTokens: number;
  maxCostMicros: number;
  periodDays: number;
  permissions: string[];
}

export interface Usage {
  periodStart: string;
  periodEnd: string;
  executions: number;
  tokens: number;
  costMicros: number;
}

export interface UsageView {
  plan: Plan;
  used: Usage;
}

export const api = {
  register(organizationName: string, email: string, password: string): Promise<Organization> {
    return call("/organizations", {
      method: "POST",
      body: JSON.stringify({ organizationName, email, password }),
    });
  },

  verify(token: string): Promise<void> {
    return call(`/verifications/${encodeURIComponent(token)}`, { method: "POST" });
  },

  signIn(email: string, password: string): Promise<User> {
    return call("/sessions", { method: "POST", body: JSON.stringify({ email, password }) });
  },

  signOut(): Promise<void> {
    return call("/sessions", { method: "DELETE" });
  },

  usage(): Promise<UsageView> {
    return call("/usage");
  },

  apiKeys(): Promise<ApiKey[]> {
    return call("/api-keys");
  },

  issueApiKey(name: string): Promise<IssuedApiKey> {
    return call("/api-keys", { method: "POST", body: JSON.stringify({ name }) });
  },

  revokeApiKey(id: string): Promise<void> {
    return call(`/api-keys/${encodeURIComponent(id)}`, { method: "DELETE" });
  },

  /** Who the browser is, or null. Every screen asks this on load. */
  async currentUser(): Promise<User | null> {
    try {
      return await call<User>("/session");
    } catch (failure) {
      if (failure instanceof ConsoleError && failure.status === 401) return null;
      throw failure;
    }
  },
};
