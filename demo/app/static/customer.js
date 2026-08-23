/* The customer's side of one purchase request.
 *
 * Every line here is presentation. What a step means to a buyer — "Comparing
 * what came back" for `propose` — belongs to this page and nowhere else: the
 * workflow names steps, and naming them twice, once for the engine and once for
 * a person, is how the two drift apart. */

const thread = document.getElementById("thread");
const trace = document.getElementById("trace");
const composer = document.getElementById("composer");
const message = document.getElementById("message");

const EXAMPLES = [
  "We need 40 replacement bearings for line 3, ideally this week.",
  "Order 12 pallets of shrink wrap for the packing hall, cost centre CC-4100.",
  "Two spare gearboxes for the mixer, whatever arrives fastest.",
];

/* Steps a buyer would recognise. A step missing from here is shown by name in
 * the trace and left out of the conversation, which is the right default: a new
 * step should not silently invent a sentence for itself. */
const NARRATION = {
  understand: "Reading the request…",
  gather: "Looking up suppliers and this quarter's budget…",
  propose: "Comparing what came back…",
  place_order: "Placing the order…",
};

let events = null;
let current = null;

/* -- talking to the server ------------------------------------------------ */

async function send(text) {
  say("mine", "You", text);
  disable(true);

  const started = await post("/api/requests", { message: text });
  follow(started.executionId);
}

async function choose(vendorId, buttons) {
  buttons.forEach((button) => (button.disabled = true));
  await post(`/api/requests/${current}/choice`, { vendorId });
}

function follow(executionId) {
  current = executionId;
  if (events) events.close();
  trace.innerHTML = "";

  identify(executionId);
  events = new EventSource(`/api/requests/${executionId}/events`);
  events.onmessage = (frame) => arrived(JSON.parse(frame.data));
  events.onerror = () => {
    /* The stream ends when the execution does, and the browser reports that as
     * an error because it cannot tell a finished stream from a lost one. The
     * `finished` event already closed things properly; this only stops the
     * browser reconnecting to a stream that has nothing left to say. */
    if (events) events.close();
  };
}

/* Which graph this run is in, named once. A version is worth showing: an
 * execution finishes in the version it started in, so this is the answer to
 * "which flow did that follow" even after the file has moved on. */
async function identify(executionId) {
  const about = document.getElementById("about");
  const state = await (await fetch(`/api/requests/${executionId}`)).json();

  document.getElementById("about-workflow").textContent = state.workflow;
  document.getElementById("about-execution").textContent = executionId;
  about.hidden = false;
}

async function post(url, body) {
  const reply = await fetch(url, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!reply.ok) {
    const detail = await reply.json().catch(() => ({}));
    say("system", "", `That did not work: ${detail.detail || reply.statusText}`);
    disable(false);
    throw new Error(reply.statusText);
  }
  return reply.json();
}

/* -- what arrives --------------------------------------------------------- */

function arrived(event) {
  record(event);

  if (event.kind === "step_started") {
    const line = NARRATION[event.step];
    if (line) say("system", "", event.attempt > 1 ? `${line} (attempt ${event.attempt})` : line);
    return;
  }
  if (event.kind === "suspended" && event.step === "await_choice") {
    offer(event.variables.options);
    return;
  }
  if (event.kind === "suspended" && event.step === "manager_approval") {
    awaitApproval(event.variables);
    return;
  }
  if (event.kind === "resumed") {
    /* Both stops resume the same way, so what to say comes from where it was
     * standing: an event carried the choice back, a person answered the approval. */
    say("system", "", event.step === "manager_approval"
      ? "The approver answered."
      : "Choice registered.");
    return;
  }
  if (event.kind === "finished") {
    conclude(event);
    disable(false);
  }
}

function offer(options) {
  const turn = say("stopped", "Supplier", "Three suppliers can do it. Which one?");
  const list = document.createElement("div");
  list.className = "options";

  const buttons = options.options.map((option) => {
    const button = document.createElement("button");
    button.className = "option" + (option.vendorId === options.recommended ? " recommended" : "");
    button.type = "button";
    button.innerHTML =
      `<span class="name"></span>` +
      `<span class="figures"><b></b><span></span></span>` +
      `<span class="why"></span>`;
    button.querySelector(".name").textContent = option.vendor;
    button.querySelector(".figures b").textContent = money(option.amount);
    button.querySelector(".figures span").textContent = `${option.leadTimeDays} day lead`;
    button.querySelector(".why").textContent = option.why;

    if (option.vendorId === options.recommended) {
      const tag = document.createElement("span");
      tag.className = "tag";
      tag.textContent = "the model would choose this";
      button.append(tag);
    }
    button.onclick = () => choose(option.vendorId, buttons);
    list.append(button);
    return button;
  });

  turn.querySelector(".said").append(list);
}

function awaitApproval(variables) {
  say("stopped", "Waiting on a person",
    `${money(variables.choice.amount)} is over the €10,000 threshold, so this ` +
    `stopped for a manager. The execution is on disk; nothing is holding a ` +
    `connection open, and this page could be closed and reopened.`);

  const open = document.createElement("a");
  open.href = "/approvals";
  open.target = "_blank";
  open.rel = "noopener";
  open.textContent = "Open the approvals page →";
  thread.lastElementChild.querySelector(".said").append(document.createElement("br"), open);
}

function conclude(event) {
  const variables = event.variables || {};

  if (variables.order) {
    say("system", "", `Ordered. ${variables.order.orderId}, ${money(variables.order.amount)}.`);
    return;
  }
  if (variables.place_orderError) {
    say("system", "",
      `Approved, then refused by the company's own code: ` +
      `${variables.place_orderError.message}. The flow declares where a refusal ` +
      `goes, so this is an outcome rather than a crash.`);
    return;
  }
  if (variables.choice) {
    say("system", "", "The manager declined it. Nothing was ordered.");
    return;
  }
  say("system", "", `Finished as ${event.status}.`);
}

/* -- the trace ------------------------------------------------------------ */

/* The trace is mostly quiet on purpose: a stop and a resume are what a reader
 * is looking for, and colouring everything would hide them. */
const TONE = {
  suspended: "stopped",
  finished: "stopped",
  step_started: "running",
  resumed: "running",
};

function record(event) {
  const row = document.createElement("div");
  row.className = "row";
  row.innerHTML = `<span class="seq"></span><span class="kind"></span><span class="step"></span>`;
  row.querySelector(".seq").textContent = event.sequence ?? "";
  const kind = row.querySelector(".kind");
  kind.textContent = event.kind;
  kind.classList.add(TONE[event.kind] ?? "quiet");

  const detail = [event.step, event.attempt > 1 ? `try ${event.attempt}` : "", event.status]
    .filter(Boolean)
    .join("  ");
  row.querySelector(".step").textContent = detail;

  trace.append(row);
  trace.scrollTop = trace.scrollHeight;
}

/* -- small things --------------------------------------------------------- */

function say(kind, who, text) {
  const turn = document.createElement("div");
  turn.className = `turn ${kind}`;
  turn.innerHTML = `<div class="who"></div><div class="said"></div>`;
  turn.querySelector(".who").textContent = who;
  turn.querySelector(".said").textContent = text;
  if (!who) turn.querySelector(".who").remove();

  thread.append(turn);
  turn.scrollIntoView({ block: "nearest" });
  return turn;
}

function money(amount) {
  return new Intl.NumberFormat("en-IE", { style: "currency", currency: "EUR",
    maximumFractionDigits: 0 }).format(amount);
}

function disable(busy) {
  composer.querySelector("button").disabled = busy;
  message.disabled = busy;
  if (!busy) message.focus();
}

composer.onsubmit = (submit) => {
  submit.preventDefault();
  const text = message.value.trim();
  if (!text) return;
  message.value = "";
  send(text).catch(() => disable(false));
};

const suggestions = document.getElementById("suggestions");
EXAMPLES.forEach((example) => {
  const button = document.createElement("button");
  button.type = "button";
  button.textContent = example.length > 52 ? example.slice(0, 52) + "…" : example;
  button.title = example;
  button.onclick = () => {
    message.value = example;
    message.focus();
  };
  suggestions.append(button);
});

say("system", "", "Ask for something, or pick one of the examples below.");
