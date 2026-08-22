/**
 * Attaching an API key to every call.
 *
 * The key travels in call metadata rather than in a request body: a request
 * carrying its own answer to "who am I" has not been authenticated, it has been
 * asked politely (DESIGN.md §23).
 *
 * Metadata per call rather than composed channel credentials, because grpc-js
 * refuses to compose call credentials onto an insecure channel — it will not
 * help you send a secret in the clear. That refusal is right, and it is also why
 * this route exists: local development is plaintext, and the same code has to
 * work there and against TLS.
 */

import * as grpc from "@grpc/grpc-js";

/**
 * The key that was passed, or the one in the environment.
 *
 * Reading `PIPEMESH_API_KEY` means a key never has to be written into code that
 * gets committed.
 */
export function resolveKey(apiKey?: string): string | undefined {
  const fromEnvironment = process.env.PIPEMESH_API_KEY?.trim();
  return apiKey || fromEnvironment || undefined;
}

/**
 * The metadata every call carries — empty when there is no key.
 *
 * Warns when a key would travel over a plaintext connection. Not refused:
 * plaintext is right on a laptop, and refusing would make every development
 * setup stand up TLS first. But a key sent unencrypted to a real deployment is a
 * leaked key, and silence is how that happens.
 */
export function authMetadata(
  apiKey: string | undefined,
  secure: boolean,
): grpc.Metadata {
  const metadata = new grpc.Metadata();
  if (!apiKey) return metadata;

  if (!secure) {
    console.warn(
      "Sending an API key over a plaintext connection. That is fine locally and a leak " +
        "anywhere else; use a secure channel against a real deployment.",
    );
  }

  metadata.set("authorization", `Bearer ${apiKey}`);
  return metadata;
}
