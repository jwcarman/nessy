// The page. It does three things: draw what has been said, post what you type, and listen.
//
// A message is accepted at once (202, empty body) and answered later: the agent works on its own
// threads, and what it says and does arrives on the stream. A message sent while the agent is
// still working is accepted too; the agent is given everything that arrived meanwhile together,
// as one message. What is waiting for a person is never kept here: the approval cards are read
// from the server's state, and read again whenever the stream says something about a call.
//
// The listening is the part worth reading. The stream is NOT the response to the message you
// sent -- it is a standing subscription to one agent, held for as long as the tab is. A turn
// started in this tab is narrated to every tab.
//
// The page subscribes FIRST and reads the state once the stream is open, and again every time it
// reopens. A stream joined with no event id starts from now and replays nothing, so what happened
// before the subscription, or while it was down, is found in the state, never assumed. Everything
// the stream and those reads draw runs on one chain, in order, and drawing is idempotent: per
// turn for answers, per key for cards.

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
// Bumped whenever the page moves to another conversation, so work still queued for the old one
// draws nothing into the new one.
let generation = 0;
// Whether a turn this page watched begin is in progress. A turn the page joined in the middle has
// only its later words on screen, so its answer is drawn whole from the state when it ends.
let watching = false;
let joined = false;

function queue(work) {
  const mine = generation;
  chain = chain
    .then(() => (mine === generation ? work() : undefined))
    .catch((error) => console.error(error));
}

function useAgent(id) {
  agentId = id;
  location.hash = id;
  localStorage.setItem("agentId", id);
}

// Shows whether the agent is in a turn. The input is never disabled: a message sent now is
// accepted. When the agent is waiting on a card, the line says it is waiting for the person.
function setWorking(isWorking) {
  working.hidden = !isWorking;
}

function waitingFor(cards) {
  working.textContent = cards.length > 0 ? "waiting for you…" : "working…";
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
  waitingFor(cards);
}

// The state of the conversation, with a time limit: a server that does not answer must not hold
// the chain, and every draw behind it, forever.
async function readState() {
  const response = await fetch(`/api/agents/${agentId}`, { signal: AbortSignal.timeout(10000) });
  if (!response.ok) throw new Error("state read answered " + response.status);
  return await response.json();
}

async function refreshCards() {
  drawCards((await readState()).approvals);
}

// The last line of a turn, when it is an assistant line: that is the turn's answer. A turn that
// failed or was refused ends in a system line, and one still going ends in whatever it said last.
function answerOf(transcript, turn) {
  const lines = transcript.filter((line) => line.turn === turn);
  const last = lines[lines.length - 1];
  return last && last.role === "assistant" ? last.text : null;
}

// The turn in progress is the last one in the story, when the agent is working.
function finishedTurns(state) {
  const turns = [];
  for (const line of state.transcript) {
    if (!turns.includes(line.turn)) turns.push(line.turn);
  }
  if (state.working) turns.pop();
  return turns;
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
      signal: AbortSignal.timeout(10000),
    });
  } catch (networkError) {
    response = null;
  }
  if (response === null || (response.status !== 202 && response.status !== 409)) {
    // The answer did not go through and the card is still waiting, so the buttons come back. A
    // 400 is a refusal and trying again will not help; anything else may.
    card.querySelectorAll("button").forEach((b) => (b.disabled = false));
    appendLine(
      "system",
      response !== null && response.status === 400
        ? "your answer was refused"
        : "your answer did not go through; try again",
    );
    return;
  }
  if (response.status === 202) {
    appendLine("system", decision === "approve" ? "you approved it" : "you denied it");
  }
  // 202 or 409 (another tab answered first, or the term ran out), the cards are redrawn from what
  // is waiting now rather than from the click.
  try {
    await refreshCards();
  } catch (error) {
    console.error(error);
  }
}

// Opens the stream for the current conversation. The first time it opens, the whole conversation
// is read and drawn. Every later open is a reconnect, and then the state is only reconciled: the
// log is not wiped, because a bubble may be mid-stream and a line typed may not be in the story
// yet.
function listen() {
  if (events) events.close();
  const source = new EventSource(`/api/agents/${agentId}/events`);
  events = source;
  let firstOpen = true;
  source.onopen = () => {
    const full = firstOpen;
    firstOpen = false;
    queue(full ? load : reconcile);
  };

  // The event names are the engine's own, as the Odyssey narrator journals them.
  const on = (name, handler) =>
    source.addEventListener(name, (e) => queue(() => handler(e)));
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
    watching = false;
    joined = false;
    setWorking(false);
  };
  on("turn-started", () => {
    streamed = false;
    watching = true;
    joined = false;
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
  // answer is read from the story and drawn here. So is the answer of a turn this page joined in
  // the middle: it has only the later deltas, a bubble that starts mid-sentence, which is removed
  // for the whole answer.
  on("answered", async (e) => {
    const whole = streamed && !joined;
    const partial = openBubble;
    const turn = JSON.parse(e.data).turn;
    idle();
    if (whole && !drawnTurns.has(turn)) {
      drawnTurns.add(turn);
    } else if (!drawnTurns.has(turn)) {
      if (partial) partial.remove();
      await drawAnswer(turn);
    } else if (partial) {
      // The story was read after the turn ended and drew its answer already; the bubble repeats it.
      partial.remove();
    }
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
  source.onerror = () => {
    // EventSource reconnects on its own, and onopen reconciles when it does. The working line is
    // left as it is until the state says otherwise.
  };
}

// The answer of one turn, drawn from the story. Turns are told apart by their id, because the
// story may already hold the next turn's lines.
async function drawAnswer(turn) {
  const state = await readState();
  if (drawnTurns.has(turn)) return;
  const answer = answerOf(state.transcript, turn);
  if (answer !== null) {
    drawnTurns.add(turn);
    appendLine("assistant", answer);
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
  let response = null;
  try {
    response = await fetch(`/api/agents/${agentId}/messages`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ text }),
    });
  } catch (networkError) {
    appendLine("system", "that message did not go through");
    return;
  }
  if (response.status === 409) {
    appendLine("system", "that conversation has ended; start a new chat");
  }
}

// Clears what is on screen: a new conversation starts from nothing.
function clearScreen() {
  log.innerHTML = "";
  approvalsSection.innerHTML = "";
  openBubble = null;
  openThinking = null;
  streamed = false;
  watching = false;
  joined = false;
  drawnTurns = new Set();
  setWorking(false);
}

// The whole conversation, drawn from the story: the first read after the stream opens, and a new
// chat. The turn in progress has only commentary in the story so far, so its answer is not marked
// drawn, and the page remembers it joined that turn in the middle.
async function load() {
  const state = await readState();
  clearScreen();
  for (const line of state.transcript) appendLine(line.role, line.text);
  for (const turn of finishedTurns(state)) {
    if (answerOf(state.transcript, turn) !== null) drawnTurns.add(turn);
  }
  joined = state.working;
  drawCards(state.approvals);
  setWorking(state.working);
}

// A reconnect: the state is read again and only reconciled with the screen. The indication and the
// cards are set from it, and the answer of any turn that ended while the stream was down is drawn,
// in turn order, if it is not on screen. A bubble left over from a turn that ended unannounced is
// replaced by its answer. Nothing already on screen is wiped, so lines typed and not yet in the
// story stay.
async function reconcile() {
  const state = await readState();
  if (!state.working && openBubble) {
    openBubble.remove();
    openBubble = null;
  }
  for (const turn of finishedTurns(state)) {
    const answer = answerOf(state.transcript, turn);
    if (answer !== null && !drawnTurns.has(turn)) {
      drawnTurns.add(turn);
      appendLine("assistant", answer);
    }
  }
  if (state.working && !watching) joined = true;
  if (!state.working) {
    watching = false;
    joined = false;
    streamed = false;
  }
  drawCards(state.approvals);
  setWorking(state.working);
}

form.addEventListener("submit", send);
// "New chat" used to mint a new id and walk away from the old one, which left an agent behind
// for every conversation anybody ever started, open and waiting. Ending the old one is the whole
// difference between starting fresh and quietly littering; what it said is kept, it just takes
// no more. The old stream is closed first and its queued work is dropped, so nothing of the old
// conversation draws into the new one.
newChatButton.addEventListener("click", async () => {
  const finished = agentId;
  if (events) events.close();
  generation += 1;
  useAgent(crypto.randomUUID());
  clearScreen();
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
listen();
