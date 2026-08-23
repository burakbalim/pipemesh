/* The approver's inbox.
 *
 * The list belongs to this application, not to the runtime: who approves what
 * is the company's business, and a runtime holding an inbox would have started
 * deciding it. What the runtime supplies is the fact that an execution is
 * stopped there, and the guarantee that answering twice counts once. */

const inbox = document.getElementById("inbox");

/* Polled rather than streamed. The list changes when somebody on another page
 * reaches an approval — rarely, and a second late is not late. */
const EVERY = 2000;

/* `null`, not "", for nothing-drawn-yet: an empty inbox has the signature "" too,
 * and conflating the two means the page stops redrawing at exactly the moment
 * the last request is decided — the card would sit there for good. */
let shown = null;

async function refresh() {
  const reply = await fetch("/api/approvals");
  if (!reply.ok) return;

  const waiting = (await reply.json()).waiting;
  const signature = waiting.map((request) => request.executionId).join(",");
  if (signature === shown) return;

  shown = signature;
  render(waiting);
}

function render(waiting) {
  inbox.innerHTML = "";
  if (waiting.length === 0) {
    inbox.innerHTML =
      `<div class="quiet-inbox">Nothing is waiting. Start a request over &euro;10,000 ` +
      `on the <a href="/">request page</a> and it will appear here.</div>`;
    return;
  }
  waiting.forEach((request) => inbox.append(card(request)));
}

function card(request) {
  const over = request.choice.amount > request.remaining;

  const element = document.createElement("article");
  element.className = "request";
  element.innerHTML = `
    <h3></h3>
    <div class="ref mono"></div>
    <dl>
      <dt>Supplier</dt><dd class="supplier"></dd>
      <dt>Amount</dt><dd class="amount"></dd>
      <dt>Lead time</dt><dd class="lead"></dd>
      <dt>Budget left</dt><dd class="remaining"></dd>
    </dl>
    <div class="decide">
      <button class="approve" type="button">Approve</button>
      <button class="reject" type="button">Reject</button>
    </div>`;

  element.querySelector("h3").textContent = `${request.quantity} × ${request.item}`;
  element.querySelector(".ref").textContent = request.requestId;
  element.querySelector(".supplier").textContent = request.choice.vendor;
  element.querySelector(".amount").textContent = money(request.choice.amount);
  element.querySelector(".lead").textContent = `${request.choice.leadTimeDays} days`;

  const remaining = element.querySelector(".remaining");
  remaining.textContent = money(request.remaining);
  if (over) {
    remaining.classList.add("over");
    /* Worth saying before the click rather than after: this one is refused by
     * the company's own code even once approved, and watching that happen is
     * the point of leaving it possible. */
    remaining.textContent += " — this order would exceed it";
  }

  const buttons = [...element.querySelectorAll("button")];
  buttons[0].onclick = () => decide(request.executionId, true, buttons);
  buttons[1].onclick = () => decide(request.executionId, false, buttons);
  return element;
}

async function decide(executionId, approved, buttons) {
  buttons.forEach((button) => (button.disabled = true));

  await fetch(`/api/approvals/${executionId}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ approved, decidedBy: "manager@demo" }),
  });
  shown = null;
  refresh();
}

function money(amount) {
  return new Intl.NumberFormat("en-IE", { style: "currency", currency: "EUR",
    maximumFractionDigits: 0 }).format(amount);
}

refresh();
setInterval(refresh, EVERY);
