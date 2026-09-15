// The page. It does three things: draw what has been said, post what you type, and listen.
//
// The listening is the part worth reading. The stream is NOT the response to the message you
// sent -- it is a standing subscription to one agent, opened when the page loads and held for as
// long as the tab is. A turn started in this tab is narrated to every tab, and because the
// narration is journaled (Odyssey), a connection that drops picks up where it left off: the
// browser hands back the last event id it saw, and the server replays what came after.

const log = document.getElementById("log");
const approvalsSection = document.getElementById("approvals");
const form = document.getElementById("send-form");
const textInput = document.getElementById("text");
const sendButton = document.getElementById("send");
const newChatButton = document.getElementById("new-chat");

let agentId = location.hash.slice(1) || localStorage.getItem("agentId") || crypto.randomUUID();
let events = null;
let openBubble = null;
let openThinking = null;
// Whether this inference call has streamed: a provider that does says the answer delta by delta
// and then whole, and drawing it twice would be wrong; one that does not says it whole, once.
let streamed = false;

function useAgent(id) {
  agentId = id;
  location.hash = id;
  localStorage.setItem("agentId", id);
}

function setBusy(busy) {
  textInput.disabled = busy;
  sendButton.disabled = busy;
}

function appendLine(role, text) {
  const div = document.createElement("div");
  div.className = "line " + role;
  div.textContent = text;
  log.appendChild(div);
  log.scrollTop = log.scrollHeight;
  return div;
}

function renderApproval(card) {
  // At-least-once narration: a card already on screen -- from the live stream, from a page
  // rebuild that raced it, or both -- draws nothing new.
  if (!card.id || approvalsSection.querySelector(`[data-call="${card.id}"]`)) return;
  const div = document.createElement("div");
  div.className = "approval-card";
  div.dataset.call = card.id;
  const title = document.createElement("div");
  title.className = "approval-tool";
  title.textContent = card.what || card.tool;
  const args = document.createElement("pre");
  args.textContent = card.args ?? "";
  const actions = document.createElement("div");
  actions.className = "approval-actions";
  const allow = document.createElement("button");
  allow.type = "button";
  allow.textContent = "Approve";
  allow.addEventListener("click", () => decide(card.id, "approve", div));
  const deny = document.createElement("button");
  deny.type = "button";
  deny.textContent = "Deny";
  deny.addEventListener("click", () => decide(card.id, "deny", div));
  actions.append(allow, deny);
  div.append(title, args, actions);
  approvalsSection.appendChild(div);
}

async function decide(callId, decision, card) {
  card.querySelectorAll("button").forEach((b) => (b.disabled = true));
  const response = await fetch(`/api/agents/${agentId}/approvals/${callId}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ decision, note: decision === "deny" ? "denied from the page" : "" }),
  });
  card.remove();
  if (response.status === 409) {
    // Someone else answered it first. Redraw rather than trust the click that lost the race.
    await load();
    return;
  }
  appendLine("system", decision === "approve" ? "you approved it" : "you denied it");
}

function listen() {
  if (events) events.close();
  events = new EventSource(`/api/agents/${agentId}/events`);

  // The event names are the engine's own, as the Odyssey narrator journals them.
  const said = (text) => {
    openThinking = null;
    if (!openBubble) openBubble = appendLine("assistant", "");
    openBubble.textContent += text;
    log.scrollTop = log.scrollHeight;
  };
  const idle = () => {
    openBubble = null;
    openThinking = null;
    streamed = false;
    setBusy(false);
  };
  events.addEventListener("turn-started", () => {
    streamed = false;
    setBusy(true);
  });
  events.addEventListener("content-delta", (e) => {
    streamed = true;
    said(JSON.parse(e.data).text);
  });
  events.addEventListener("thinking-delta", (e) => {
    if (!openThinking) openThinking = appendLine("thinking", "");
    openThinking.textContent += JSON.parse(e.data).text;
    log.scrollTop = log.scrollHeight;
  });
  events.addEventListener("commentary", (e) => {
    if (!streamed) said(JSON.parse(e.data).text);
  });
  // A request names the tools; what happens to each call is told later, by call id. Each is a
  // line of its own rather than an edit to the request's line, which is what the story shows too.
  events.addEventListener("actions-requested", (e) => {
    openThinking = null;
    openBubble = null;
    streamed = false;
    for (const name of JSON.parse(e.data).toolNames) appendLine("tool", "🔧 " + name);
  });
  events.addEventListener("approval", (e) => renderApproval(JSON.parse(e.data)));
  events.addEventListener("call-approved", () => appendLine("tool", "approved"));
  events.addEventListener("call-denied", (e) =>
    appendLine("tool", "denied: " + JSON.parse(e.data).reason),
  );
  events.addEventListener("call-finished", () => appendLine("tool", "done"));
  events.addEventListener("call-failed", (e) =>
    appendLine("tool", "failed: " + JSON.parse(e.data).message),
  );
  events.addEventListener("answered", (e) => {
    if (!streamed) said(JSON.parse(e.data).text);
    idle();
  });
  events.addEventListener("turn-failed", () => {
    appendLine("system", "the agent could not answer");
    idle();
  });
  events.addEventListener("turn-refused", () => {
    appendLine("system", "the agent declined to answer");
    idle();
  });
  events.addEventListener("terminated", idle);
  events.onerror = () => {
    // EventSource reconnects on its own; the input must not stay disabled while it does.
    setBusy(false);
  };
}

async function send(event) {
  event.preventDefault();
  const text = textInput.value.trim();
  if (!text) return;
  textInput.value = "";
  appendLine("user", text);
  setBusy(true);
  // 202 and an empty body: the line is now the agent's problem, and everything it says about it
  // arrives on the stream this page is already listening to.
  await fetch(`/api/agents/${agentId}/messages`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ text }),
  });
}

async function load() {
  log.innerHTML = "";
  approvalsSection.innerHTML = "";
  openBubble = null;
  openThinking = null;
  const state = await (await fetch(`/api/agents/${agentId}`)).json();
  for (const line of state.transcript) appendLine(line.role, line.text);
  for (const card of state.approvals) renderApproval(card);
  setBusy(false);
}

form.addEventListener("submit", send);
// "New chat" used to mint a new id and walk away from the old one, which left an agent behind
// for every conversation anybody ever started, open and waiting. Ending the old one is the whole
// difference between starting fresh and quietly littering; what it said is kept, it just takes
// no more.
newChatButton.addEventListener("click", async () => {
  const finished = agentId;
  useAgent(crypto.randomUUID());
  await load();
  listen();
  // After the switch, deliberately: the new conversation should open even if this fails, and an
  // ending the server never heard is a leaked agent, not a broken page.
  try {
    await fetch(`/api/agents/${finished}`, { method: "DELETE" });
  } catch (ignored) {
    // Nothing to tell the person: their new chat is already open and working.
  }
});

useAgent(agentId);
load().then(listen);
