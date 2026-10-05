// The page. It does three things: draw what has been said, post what you type, and listen.
//
// A message is accepted at once (202, empty body) and answered later: the agent works on its own
// threads, and what it says and does arrives on the stream. A message sent while the agent is
// still working is accepted too; the agent is given everything that arrived meanwhile together,
// as one message. What is waiting for a person is never kept here: the approval cards are read
// from the server's state, and read again whenever the stream says something about a call.
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
const working = document.getElementById("working");
const newChatButton = document.getElementById("new-chat");

let agentId = location.hash.slice(1) || localStorage.getItem("agentId") || crypto.randomUUID();
let events = null;
let openBubble = null;
let openThinking = null;
// Whether this inference call has streamed. A provider that streams says commentary delta by
// delta as it writes it; one that does not says it only as a commentary event, and drawing both
// would show it twice.
let streamed = false;
// The turns whose answer is on screen, so an answer is drawn once however it got here: from the
// transcript on load, as deltas, or from a late or replayed "answered" event.
let drawnTurns = new Set();
// Every draw the stream causes runs on this one chain, in the order the events arrived, so a slow
// read for one turn's answer cannot land after the next turn's first words.
let chain = Promise.resolve();

function useAgent(id) {
  agentId = id;
  location.hash = id;
  localStorage.setItem("agentId", id);
}

// Shows whether the agent is in a turn. The input is never disabled: a message sent now is accepted.
function setWorking(isWorking) {
  working.hidden = !isWorking;
}

function appendLine(role, text) {
  const div = document.createElement("div");
  div.className = "line " + role;
  div.textContent = text;
  log.appendChild(div);
  log.scrollTop = log.scrollHeight;
  return div;
}

// Draws the approval cards the server says are waiting: adds the new ones, removes the ones no
// longer waiting, and leaves a card already on screen alone. A card's id is the call's
// idempotency key, which is also what an answer is addressed with.
function drawCards(cards) {
  const waiting = new Set(cards.map((card) => card.id));
  for (const shown of approvalsSection.querySelectorAll("[data-call]")) {
    if (!waiting.has(shown.dataset.call)) shown.remove();
  }
  for (const card of cards) renderApproval(card);
}

async function readState() {
  return await (await fetch(`/api/agents/${agentId}`)).json();
}

async function refreshCards() {
  drawCards((await readState()).approvals);
}

function renderApproval(card) {
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

async function decide(key, decision, card) {
  card.querySelectorAll("button").forEach((b) => (b.disabled = true));
  let response = null;
  try {
    response = await fetch(`/api/agents/${agentId}/approvals/${key}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ decision, note: decision === "deny" ? "denied from the page" : "" }),
    });
  } catch (networkError) {
    response = null;
  }
  if (response === null || (response.status !== 202 && response.status !== 409)) {
    // The answer did not go through and the card is still waiting: let the person try again.
    card.querySelectorAll("button").forEach((b) => (b.disabled = false));
    appendLine("system", "your answer did not go through; try again");
    return;
  }
  if (response.status === 202) {
    appendLine("system", decision === "approve" ? "you approved it" : "you denied it");
  }
  // 202 or 409 (another tab answered first, or the term ran out), the cards are redrawn from what
  // is waiting now rather than from the click.
  await refreshCards();
}

function listen() {
  if (events) events.close();
  events = new EventSource(`/api/agents/${agentId}/events`);

  // The event names are the engine's own, as the Odyssey narrator journals them.
  const on = (name, handler) =>
    events.addEventListener(name, (e) => {
      chain = chain.then(() => handler(e)).catch((error) => console.error(name, error));
    });
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
    setWorking(false);
  };
  on("turn-started", () => {
    streamed = false;
    setWorking(true);
  });
  on("content-delta", (e) => {
    streamed = true;
    said(JSON.parse(e.data).text);
  });
  on("thinking-delta", (e) => {
    if (!openThinking) openThinking = appendLine("thinking", "");
    openThinking.textContent += JSON.parse(e.data).text;
    log.scrollTop = log.scrollHeight;
  });
  on("commentary", (e) => {
    if (!streamed) said(JSON.parse(e.data).text);
  });
  // A request names each call and its tool; what happens to a call is told later without
  // its tool, so the page remembers which tool each id is. Each is a line of its own rather than an
  // edit to the request's line, which is what the story shows too.
  const toolOf = new Map();
  const named = (e) => toolOf.get(JSON.parse(e.data).callId) ?? "call";
  on("actions-requested", (e) => {
    openThinking = null;
    openBubble = null;
    streamed = false;
    for (const call of JSON.parse(e.data).calls) {
      toolOf.set(call.callId, call.toolName);
      appendLine("tool", "🔧 " + call.toolName + ": " + call.action);
    }
  });
  // An approval was deferred, or a call was settled: what is waiting for a person has changed, so
  // the cards are read again.
  on("approval-deferred", () => refreshCards());
  on("call-approved", (e) => {
    appendLine("tool", named(e) + " approved");
    refreshCards();
  });
  on("call-denied", (e) => {
    appendLine("tool", named(e) + " denied: " + JSON.parse(e.data).reason);
    refreshCards();
  });
  on("call-finished", (e) => {
    appendLine("tool", named(e) + " done");
    refreshCards();
  });
  on("call-failed", (e) => {
    appendLine("tool", named(e) + " failed: " + JSON.parse(e.data).message);
    refreshCards();
  });
  // Says that an answer happened and which turn it ended, and carries no words. A streaming
  // provider has already shown them as deltas; one that does not stream has shown nothing, so the
  // answer is read from the story and drawn here.
  on("answered", async (e) => {
    const wasStreamed = streamed;
    const turn = JSON.parse(e.data).turn;
    idle();
    if (wasStreamed) drawnTurns.add(turn);
    else await drawAnswer(turn);
  });
  on("turn-failed", () => {
    appendLine("system", "the agent could not answer");
    idle();
  });
  on("turn-refused", () => {
    appendLine("system", "the agent declined to answer");
    idle();
  });
  on("turn-stopped", (e) => {
    appendLine("system", "the agent stopped the turn: " + JSON.parse(e.data).reason);
    idle();
  });
  on("terminated", idle);
  events.onerror = () => {
    // EventSource reconnects on its own and resumes from the last event it saw, so the working
    // line is left as it is.
  };
}

// The answer of one turn, drawn from the story: the turn's last assistant line. Turns are told
// apart by their id, because the story may already hold the next turn's lines.
async function drawAnswer(turn) {
  const state = await readState();
  if (drawnTurns.has(turn)) return;
  const mine = state.transcript.filter((line) => line.turn === turn && line.role === "assistant");
  if (mine.length > 0) {
    drawnTurns.add(turn);
    appendLine("assistant", mine[mine.length - 1].text);
  }
}

async function send(event) {
  event.preventDefault();
  const text = textInput.value.trim();
  if (!text) return;
  textInput.value = "";
  // Drawn as typed. After a reload the story shows what the agent was given, and messages sent
  // while it was busy are one line there, joined.
  appendLine("user", text);
  // 202 and an empty body: the line is now the agent's problem, and everything it says about it
  // arrives on the stream this page is already listening to. Sent while a turn is in progress, it
  // is accepted all the same and answered with whatever else arrived meanwhile.
  const response = await fetch(`/api/agents/${agentId}/messages`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ text }),
  });
  if (response.status === 409) {
    appendLine("system", "that conversation has ended; start a new chat");
  }
}

async function load() {
  drawnTurns = new Set();
  log.innerHTML = "";
  approvalsSection.innerHTML = "";
  openBubble = null;
  openThinking = null;
  const state = await readState();
  for (const line of state.transcript) appendLine(line.role, line.text);
  // A turn that has an assistant line has been drawn, except the one in progress, which is the
  // last, and whose lines so far are commentary rather than its answer.
  drawnTurns = new Set();
  const last = state.transcript.length > 0 ? state.transcript[state.transcript.length - 1].turn : 0;
  for (const line of state.transcript) {
    if (line.role === "assistant" && !(state.working && line.turn === last)) {
      drawnTurns.add(line.turn);
    }
  }
  drawCards(state.approvals);
  setWorking(state.working);
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
