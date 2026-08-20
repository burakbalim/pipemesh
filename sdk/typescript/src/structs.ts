/** JSON on the wire, as `google.protobuf.Struct`. */

/**
 * Encodes plain JavaScript as a `google.protobuf.Struct`.
 *
 * Written out rather than left to the loader: passing an object straight through
 * produced a Struct whose numbers were not numbers, and the workflow failed on a
 * condition that could not compare them. An encoding bug that reaches the
 * runtime as a failed step is a long way from where it happened.
 */
export function toStruct(value: Record<string, unknown>): unknown {
  const fields: Record<string, unknown> = {};
  for (const [name, entry] of Object.entries(value)) {
    fields[name] = toValue(entry);
  }
  return { fields };
}

function toValue(value: unknown): unknown {
  if (value === null || value === undefined) {
    return { nullValue: "NULL_VALUE" };
  }
  if (typeof value === "number") {
    return { numberValue: value };
  }
  if (typeof value === "boolean") {
    return { boolValue: value };
  }
  if (typeof value === "string") {
    return { stringValue: value };
  }
  if (Array.isArray(value)) {
    return { listValue: { values: value.map(toValue) } };
  }
  return { structValue: toStruct(value as Record<string, unknown>) };
}

/** The same translation on the way back. */
export function fromStruct(struct: any): Record<string, unknown> {
  const value: Record<string, unknown> = {};
  for (const [name, entry] of Object.entries(struct?.fields ?? {})) {
    value[name] = fromValue(entry);
  }
  return value;
}

function fromValue(value: any): unknown {
  switch (value?.kind) {
    case "numberValue":
      return value.numberValue;
    case "boolValue":
      return value.boolValue;
    case "stringValue":
      return value.stringValue;
    case "listValue":
      return (value.listValue?.values ?? []).map(fromValue);
    case "structValue":
      return fromStruct(value.structValue);
    default:
      return null;
  }
}
