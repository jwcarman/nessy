// The page. It does three things: draw what has been said, post what you type, and listen.
//
// The listening is the part worth reading. The stream is NOT the response to the message you
// sent -- it is a standing subscription to one agent, opened when the page loads and held for as
// long as the tab is. That is what the engine actually offers: a turn started in this tab is
// narrated to every tab, an answer that lands while you are away is still there when you come
// back, and a tool that finishes an hour later has somewhere to report.

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
const toolLines = new Map();

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

  events.addEventListener("busy", () => setBusy(true));
  events.addEventListener("idle", () => {
    openBubble = null;
    openThinking = null;
    toolLines.clear();
    setBusy(false);
  });
  events.addEventListener("delta", (e) => {
    openThinking = null;
    if (!openBubble) openBubble = appendLine("assistant", "");
    openBubble.textContent += JSON.parse(e.data).text;
    log.scrollTop = log.scrollHeight;
  });
  events.addEventListener("thinking", (e) => {
    if (!openThinking) openThinking = appendLine("thinking", "");
    openThinking.textContent += JSON.parse(e.data).text;
    log.scrollTop = log.scrollHeight;
  });
  events.addEventListener("tool-requested", (e) => {
    const payload = JSON.parse(e.data);
    openThinking = null;
    openBubble = null;
    toolLines.set(payload.id, appendLine("tool", "🔧 " + (payload.what || payload.name)));
  });
  events.addEventListener("approval", (e) => renderApproval(JSON.parse(e.data)));
  events.addEventListener("tool-decided", (e) => {
    const payload = JSON.parse(e.data);
    const line = toolLines.get(payload.id);
    if (line) line.textContent += payload.allowed ? " — approved" : " — denied";
  });
  events.addEventListener("tool-completed", (e) => {
    const payload = JSON.parse(e.data);
    const line = toolLines.get(payload.id);
    if (line) line.textContent += payload.error ? " — failed" : " — done";
  });
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
  toolLines.clear();
  const state = await (await fetch(`/api/agents/${agentId}`)).json();
  for (const line of state.transcript) appendLine(line.role, line.text);
  for (const card of state.approvals) renderApproval(card);
  setBusy(false);
}

form.addEventListener("submit", send);
// "New chat" used to mint a new id and walk away from the old one, which left an agent behind
// for every conversation anybody ever started -- a state row and a transcript, forever. Ending
// the old one is the whole difference between starting fresh and quietly littering.
newChatButton.addEventListener("click", async () => {
  const finished = agentId;
  useAgent(crypto.randomUUID());
  await load();
  listen();
  // After the switch, deliberately: the new conversation should open even if this fails, and a
  // forget the server never heard is a leaked agent, not a broken page.
  try {
    await fetch(`/api/agents/${finished}`, { method: "DELETE" });
  } catch (ignored) {
    // Nothing to tell the person: their new chat is already open and working.
  }
});

useAgent(agentId);
load().then(listen);
