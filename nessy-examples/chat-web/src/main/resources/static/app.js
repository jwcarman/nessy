// The page. It does three things: draw what has been said, post what you type, and listen.
//
// A message is accepted at once (202, empty body) and answered later: the agent works on its own
// threads, and what it does arrives on the stream as events: the words of a provider that streams,
// the tools it calls, how each call went. The answer's words are not an event of their own; the
// "answered" event only says which turn it ended, and the page reads the words from the state when
// it did not see them stream. A message sent while the agent is still working is accepted too; the
// agent is given everything that arrived meanwhile together, as one message. What is waiting for a
// person is never kept here: the approval cards are read from the server's state, and read again
// whenever the stream says something about a call.
//
// The listening is the part worth reading. The stream is NOT the response to the message you
// sent -- it is a standing subscription to one agent, held for as long as the tab is. A turn
// started in this tab is narrated to every tab.
//
// The page subscribes FIRST and reads the state every time the stream opens. A stream opened with
// no event id starts from now and replays nothing; a connection the browser restores after it has
// heard an event hands that event's id back, and the server replays everything after it. So each
// open is one of two things. With a replay, the screen is still right as far as it goes and the
// replay carries on from there. Without one, events may have been missed, so the page takes what
// it shows of every turn it has not seen end from the state, and joins the turn in progress in
// the middle. A stream the server refused (anything but an event stream) is closed for good by the
// browser, so the page opens a new one, which replays nothing. Everything the stream and those
// reads draw runs on one chain, in order.
//
// Every line on screen is tagged with its turn, except a line typed that no turn has taken up yet
// and a notice the page writes itself; its note on a decision is tagged with the card's turn. A
// turn the page did not watch from its start is drawn whole from the state when it ends, in place
// of whatever the page showed of it but its notes on decisions.
//
// An answer keeps the text the agent wrote, its Markdown source, in its data-source attribute. It
// is drawn from that text by markdown-it as it streams and when its turn is drawn again from the
// state, and the copy button copies that text. Everything else on screen is plain text.

const log = document.getElementById("log");
const approvalsSection = document.getElementById("approvals");
const form = document.getElementById("send-form");
const textInput = document.getElementById("text");
const working = document.getElementById("working");
const newChatButton = document.getElementById("new-chat");
const confirmNew = document.getElementById("confirm-new");
const greeting = document.getElementById("greeting");
const jumpButton = document.getElementById("jump");

// The model's text is not trusted. markdown-it, loaded before this script, draws it with raw HTML
// off, so a tag in the text is shown as text, and its link check refuses javascript:, vbscript:,
// file: and most data: targets. Images are off, so an answer cannot make the browser fetch
// anything. Links open in a new tab. Null when the library did not load: answers are then plain
// text.
const markdown = makeMarkdown();

function makeMarkdown() {
  if (typeof window.markdownit !== "function") return null;
  const md = window.markdownit({ html: false, linkify: true, breaks: true });
  md.disable("image");
  const openLink =
    md.renderer.rules.link_open ??
    ((tokens, index, options, env, self) => self.renderToken(tokens, index, options));
  md.renderer.rules.link_open = (tokens, index, options, env, self) => {
    tokens[index].attrSet("target", "_blank");
    tokens[index].attrSet("rel", "noopener noreferrer");
    return openLink(tokens, index, options, env, self);
  };
  // A fenced code block shows its language, escaped by the library, as a small label.
  const fence = md.renderer.rules.fence;
  md.renderer.rules.fence = (tokens, index, options, env, self) => {
    const language = tokens[index].info.trim().split(/\s+/)[0];
    const label =
      language === "" ? "" : `<span class="code-language">${md.utils.escapeHtml(language)}</span>`;
    return `<div class="code-block">${label}${fence(tokens, index, options, env, self)}</div>`;
  };
  return md;
}

// Draws an answer's source into its element. This is the one place the page sets HTML, and it is
// only ever markdown-it's rendering, with raw HTML off.
function renderAnswer(source, into) {
  if (markdown === null) {
    into.textContent = source;
    into.style.whiteSpace = "pre-wrap";
  } else {
    into.innerHTML = markdown.render(source);
  }
}

// Every event the narrator names. The page listens for all of them, including those it draws
// nothing for, because the browser keeps the id of any event it receives and a reconnect then
// replays: the page must know when that will happen.
const EVENT_NAMES = [
  "turn-started",
  "thinking",
  "answered",
  "turn-stopped",
  "turn-failed",
  "turn-refused",
  "inference-retried",
  "commentary",
  "actions-requested",
  "call-approved",
  "call-denied",
  "call-finished",
  "call-failed",
  "terminated",
  "approval-sought",
  "approval-deferred",
  "call-deferred",
  "thinking-delta",
  "content-delta",
];

let agentId = location.hash.slice(1) || localStorage.getItem("agentId") || crypto.randomUUID();
let events = null;
// Every draw runs on this one chain, in the order the events arrived, so a slow read for one
// turn's answer cannot land after the next turn's first words.
let chain = Promise.resolve();
// Bumped whenever the page moves to another conversation, so work for the old one draws nothing
// into the new one.
let generation = 0;
// The turns whose ending is on screen.
let drawnTurns = new Set();
// The lines this page typed that are not yet part of any turn on screen, oldest first.
let pendingTyped = [];
// The tool of each call, by call id and by the key the call runs under. A request names them;
// what happens to a call is told later without its tool, so a page opened after the request learns
// the tool from the approval card that is waiting.
let toolByCall = new Map();
let toolByKey = new Map();
// While Up and Down are bringing back earlier messages: what they are, newest first, which one is in
// the box (-1 for none yet), and the draft that was there before. Null otherwise.
let recall = null;
// Whether the view stays at the bottom as content arrives: true while the person is at, or within
// a few pixels of, the bottom of the conversation.
let following = true;
// The answers waiting to be drawn again from their source at the next frame.
let toDraw = new Set();
// The countdown on the approval cards, while there are cards.
let ticking = null;
// The turn being narrated, and what the page has drawn of it so far: whether it saw the turn
// start (and so every event of it since), whether the current model call streamed its words, the
// bubble those words go into, and the thinking line. Reset in one place, follow().
let live = null;
follow(null);

// Starts following a turn, or none, with nothing of it drawn live yet. A bubble the turn was still
// writing is complete now.
function follow(turn, watched = false) {
  if (live !== null && live.bubble !== null) finishAnswer(live.bubble);
  live = { turn, watched, streamed: false, bubble: null, thinking: null };
}

// The turn the live events belong to, once an event names it. Lines drawn before the page knew
// the turn are tagged with it now. A turn the page did not see start was joined in the middle.
function claim(turn) {
  if (live.turn === turn) return;
  if (live.turn !== null) follow(null);
  live.turn = turn;
  for (const line of [live.bubble, live.thinking]) if (line) line.dataset.turn = String(turn);
}

function queue(work) {
  const mine = generation;
  chain = chain
    .then(() => (mine === generation ? work() : undefined))
    .catch((error) => {
      if (mine === generation) console.error(error);
    });
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

// A line of the conversation. An answer is drawn from its source text and has a copy button; one
// still streaming gets its button when it is complete.
function makeLine(role, text, turn, streaming = false) {
  const div = document.createElement("div");
  div.className = "line " + role;
  if (role === "assistant") {
    div.dataset.source = text;
    const answer = document.createElement("div");
    answer.className = "answer";
    div.appendChild(answer);
    renderAnswer(text, answer);
    if (!streaming) addCopyButton(div);
  } else {
    div.textContent = text;
  }
  if (turn !== null && turn !== undefined) div.dataset.turn = String(turn);
  return div;
}

// Draws a line at the end of the log, tagged with its turn, or with none.
function appendLine(role, text, turn, streaming = false) {
  const div = makeLine(role, text, turn, streaming);
  log.appendChild(div);
  keepView();
  return div;
}

// Draws an answer again from its source, in its own bubble only.
function drawAnswerText(line) {
  toDraw.delete(line);
  renderAnswer(line.dataset.source, line.children[0]);
}

// A streaming answer is drawn again at most once a frame, however many pieces arrive in it.
function drawSoon(line) {
  if (toDraw.has(line)) return;
  toDraw.add(line);
  requestAnimationFrame(() => {
    if (!toDraw.has(line)) return;
    drawAnswerText(line);
    keepView();
  });
}

// A streamed answer is complete: drawn from all of its source, and given its copy button. While it
// streams it holds only its drawing.
function finishAnswer(line) {
  if (line.children.length > 1) return;
  drawAnswerText(line);
  addCopyButton(line);
}

function addCopyButton(line) {
  const copy = document.createElement("button");
  copy.type = "button";
  copy.className = "copy";
  copy.textContent = "Copy";
  copy.addEventListener("click", () => copyAnswer(line, copy));
  line.appendChild(copy);
}

// Copies the answer as the agent wrote it, Markdown and all, and says so on the button for a
// moment.
async function copyAnswer(line, button) {
  let said = "Copied";
  try {
    await navigator.clipboard.writeText(line.dataset.source);
  } catch (refused) {
    said = "Not copied";
  }
  button.textContent = said;
  setTimeout(() => {
    button.textContent = "Copy";
  }, 1500);
}

// Keeps the newest content in view while the person is at the bottom. Scrolled up, the view stays
// where it is and the "jump to latest" button shows.
function keepView() {
  if (following) log.scrollTop = log.scrollHeight;
  jumpButton.hidden = following;
}

function atBottom() {
  return log.scrollHeight - log.scrollTop - log.clientHeight <= 8;
}

// Draws the approval cards the server says are waiting: adds the new ones, removes the ones no
// longer waiting, and leaves a card already on screen alone. A card's id is the call's
// idempotency key, which is also what an answer is addressed with.
function drawCards(cards) {
  const waiting = new Set(cards.map((card) => card.id));
  for (const shown of approvalsSection.querySelectorAll("[data-call]")) {
    if (!waiting.has(shown.dataset.call)) shown.remove();
  }
  for (const card of cards) {
    toolByKey.set(card.id, card.tool);
    renderApproval(card);
  }
  waitingFor(cards);
  tickDeadlines();
}

// How long each card has left, counted down once a second from the deadline the state gave it.
// The card itself goes only when the state says it is no longer waiting.
function tickDeadlines() {
  const clocks = approvalsSection.querySelectorAll("[data-deadline]");
  for (const clock of clocks) {
    clock.textContent = timeLeft(Number(clock.dataset.deadline) - Date.now());
  }
  if (clocks.length > 0 && ticking === null) {
    ticking = setInterval(tickDeadlines, 1000);
  } else if (clocks.length === 0 && ticking !== null) {
    clearInterval(ticking);
    ticking = null;
  }
}

function timeLeft(ms) {
  if (ms <= 0) return "expired";
  const seconds = Math.ceil(ms / 1000);
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  const twoDigits = (n) => String(n).padStart(2, "0");
  const clock = hours > 0 ? `${hours}:${twoDigits(minutes)}` : String(minutes);
  return `${clock}:${twoDigits(seconds % 60)} left`;
}

// The state of the conversation, with a time limit: a server that does not answer must not hold
// the chain, and every draw behind it, forever. A read that comes back after the page moved to
// another conversation is thrown away.
async function readState() {
  const mine = generation;
  const response = await fetch(`/api/agents/${agentId}`, { signal: AbortSignal.timeout(10000) });
  if (!response.ok) throw new Error("state read answered " + response.status);
  const state = await response.json();
  if (mine !== generation) throw new Error("the page moved to another conversation");
  return state;
}

async function refreshCards() {
  drawCards((await readState()).approvals);
}

// Whether a turn's lines in the story end it: an answer, or the system line of a turn that failed
// or was refused. A turn in progress never ends in one (what it says before a call is followed by
// the call's lines). A turn stopped by a policy has no ending in the story.
function isFinished(story) {
  const last = story[story.length - 1];
  return last !== undefined && (last.role === "assistant" || last.role === "system");
}

// The story's lines, by turn, in turn order.
function byTurn(transcript) {
  const turns = new Map();
  for (const line of transcript) {
    if (!turns.has(line.turn)) turns.set(line.turn, []);
    turns.get(line.turn).push(line);
  }
  return turns;
}

function linesOf(turn) {
  return [...log.children].filter((line) => line.dataset.turn === String(turn));
}

// Where a turn not on screen goes: before the first line of a later turn or the first line typed
// that is not in the story yet; at the end when there is neither.
function placeFor(turn) {
  return (
    [...log.children].find(
      (line) =>
        pendingTyped.includes(line) ||
        (line.dataset.turn !== undefined && Number(line.dataset.turn) > turn),
    ) ?? null
  );
}

// The typed lines a turn's user line shows. Lines typed while the agent was busy are given to it
// as one input, their texts joined with a blank line, so each is looked for as a part of the
// story's line, in the order they were typed. They are taken out of the pending list, as are any
// typed before them that no turn in the story shows (the turn they started was stopped and left
// no trace in it); those stay on screen as typed.
function takeTyped(userText) {
  const taken = [];
  let from = 0;
  let lastTaken = -1;
  pendingTyped.forEach((typed, index) => {
    const text = typed.textContent;
    for (let at = userText.indexOf(text, from); at !== -1; at = userText.indexOf(text, at + 1)) {
      const starts = at === 0 || userText.startsWith("\n\n", at - 2);
      const end = at + text.length;
      const ends = end === userText.length || userText.startsWith("\n\n", end);
      if (starts && ends) {
        taken.push(typed);
        lastTaken = index;
        from = end;
        return;
      }
    }
  });
  pendingTyped = pendingTyped.slice(lastTaken + 1).filter((typed) => !taken.includes(typed));
  return taken;
}

// Draws one turn from the story, in place of whatever the page shows of it: its own lines, and
// the typed lines its user line now shows when that line is not on screen yet. The page's notes
// on a decision stay, each directly after the story's request line for its tool. A turn the story
// no longer holds (a stopped turn, once the next one starts) keeps what is on screen. An ending the
// story does not hold is drawn after it.
function drawTurn(turn, story, ending = null) {
  const shown = linesOf(turn);
  const notes = shown.filter((line) => line.dataset.tool !== undefined);
  const own = shown.filter((line) => !notes.includes(line));
  const user = story.find((line) => line.role === "user");
  const takes = user && !own.some((line) => line.classList.contains("user"));
  const replaced = story.length > 0 ? [...own, ...(takes ? takeTyped(user.text) : [])] : [];
  let before;
  if (replaced.length > 0) {
    before = [...log.children].find((line) => replaced.includes(line) || notes.includes(line));
  } else if (shown.length > 0) {
    before = shown[shown.length - 1].nextSibling;
  } else {
    before = placeFor(turn);
  }
  const lines = story.map((line) => makeLine(line.role, line.text, turn));
  if (story.length > 0) placeNotes(lines, notes);
  if (ending !== null) lines.push(makeLine("system", ending, turn));
  // A marker holds the place, because the line it is taken from may itself move.
  const marker = makeLine("marker", "", null);
  log.insertBefore(marker, before);
  for (const line of story.length > 0 ? [...replaced, ...notes] : []) line.remove();
  for (const line of lines) log.insertBefore(line, marker);
  marker.remove();
  keepView();
}

// Puts each note on a decision after the request line for its tool, in the order the notes were
// written; a second note for the same tool goes after the next such line. A note with no request
// line to follow goes last.
function placeNotes(lines, notes) {
  const next = new Map();
  for (const note of notes) {
    const request = "🔧 " + note.dataset.tool;
    const from = next.get(note.dataset.tool) ?? 0;
    const at = lines.findIndex((line, i) => i >= from && line.textContent === request);
    let index = at === -1 ? lines.length : at + 1;
    while (index < lines.length && lines[index].dataset.tool !== undefined) index++;
    lines.splice(index, 0, note);
    next.set(note.dataset.tool, index + 1);
  }
}

// A turn that ended without the page watching all of it, drawn whole from the state.
async function redrawTurn(turn, ending = null) {
  const story = (await readState()).transcript.filter((line) => line.turn === turn);
  drawTurn(turn, story, ending);
  if (ending !== null || isFinished(story)) drawnTurns.add(turn);
}

// The answer of a turn the page watched, from the state: a provider that does not stream sent no
// words on the stream. Turns are told apart by their id, because the story may already hold the
// next turn's lines.
async function drawAnswer(turn) {
  const story = (await readState()).transcript.filter((line) => line.turn === turn);
  const last = story[story.length - 1];
  if (last !== undefined && last.role === "assistant") {
    appendLine("assistant", last.text, turn);
    drawnTurns.add(turn);
  }
}

// A stream opened without a replay: events may have been missed, so the live turn's half-drawn
// words go, every turn the page has not seen end is drawn from the state, and the turn in
// progress, if any, is followed as joined in the middle. On the first open this draws the whole
// conversation.
function catchUp(state) {
  for (const line of [live.bubble, live.thinking]) if (line) line.remove();
  follow(null);
  const turns = byTurn(state.transcript);
  const last = [...turns.keys()].pop();
  for (const [turn, story] of turns) {
    if (drawnTurns.has(turn)) continue;
    drawTurn(turn, story);
    if (turn === last && state.working && !isFinished(story)) follow(turn);
    else drawnTurns.add(turn);
  }
}

// Every open reads the state: the cards and the working line are set from it, and an open with no
// replay catches up from it.
async function reconcile(replays) {
  const state = await readState();
  if (!replays) catchUp(state);
  drawCards(state.approvals);
  setWorking(state.working);
  // The greeting waits for the first read, so a conversation with history does not flash it; the
  // stylesheet hides it whenever the conversation has lines.
  greeting.hidden = false;
}

// Words of the answer being written: added to the open bubble's source, which is drawn again.
function say(text) {
  live.thinking = null;
  if (!live.bubble) live.bubble = appendLine("assistant", "", live.turn, true);
  live.bubble.dataset.source += text;
  drawSoon(live.bubble);
}

// A request names each call and its tool; what happens to a call is told later without its tool,
// so the page remembers which tool each id is, and falls back to the tool on the approval card
// with the same key when it was opened after the request. Each is a line of its own rather than
// an edit to the request's line, which is what the story shows too.
function named(call) {
  return toolByCall.get(call.callId) ?? toolByKey.get(call.idempotencyKey) ?? "call";
}

// The end of a turn. A turn the page watched from its start is finished live: a streamed answer is
// already on screen, an answer that was not streamed is read from the state, and an ending is a
// line of its own. Any other turn is drawn whole from the state.
async function ended(turn, ending, inStory) {
  claim(turn);
  const { watched, streamed, bubble } = live;
  follow(null);
  setWorking(false);
  focusBox();
  if (watched && ending === null) {
    if (streamed) {
      drawnTurns.add(turn);
      return;
    }
    if (bubble) bubble.remove();
    await drawAnswer(turn);
  } else if (watched) {
    appendLine("system", ending, turn);
    drawnTurns.add(turn);
  } else {
    await redrawTurn(turn, inStory ? null : ending);
  }
}

const handlers = {
  "turn-started": ({ turn }) => {
    // A turn the state already showed was joined, not watched from its start.
    const known = drawnTurns.has(turn) || linesOf(turn).length > 0;
    follow(turn, !known);
    if (!known) {
      for (const typed of pendingTyped) typed.dataset.turn = String(turn);
      pendingTyped = [];
    }
    setWorking(true);
  },
  "content-delta": ({ text }) => {
    live.streamed = true;
    say(text);
  },
  "thinking-delta": ({ text }) => {
    if (!live.thinking) live.thinking = appendLine("thinking", "", live.turn);
    live.thinking.textContent += text;
    keepView();
  },
  // A provider that streams says commentary delta by delta as it writes it; one that does not says
  // it only as a commentary event, and drawing both would show it twice.
  commentary: ({ text }) => {
    if (!live.streamed) say(text);
  },
  "actions-requested": (request) => {
    claim(request.turn);
    if (live.bubble) finishAnswer(live.bubble);
    live.bubble = null;
    live.thinking = null;
    live.streamed = false;
    for (const call of request.calls) {
      toolByCall.set(call.callId, call.toolName);
      toolByKey.set(call.idempotencyKey, call.toolName);
      appendLine("tool", "🔧 " + call.toolName + ": " + call.action, live.turn);
    }
  },
  // An approval was deferred, or a call was settled: what is waiting for a person has changed, so
  // the cards are read again.
  "approval-deferred": () => refreshCards(),
  "call-approved": (call) => {
    appendLine("tool", named(call) + " approved", live.turn);
    return refreshCards();
  },
  "call-denied": (call) => {
    appendLine("tool", named(call) + " denied: " + call.reason, live.turn);
    return refreshCards();
  },
  "call-finished": (call) => {
    appendLine("tool", named(call) + " done", live.turn);
    return refreshCards();
  },
  "call-failed": (call) => {
    appendLine("tool", named(call) + " failed: " + call.message, live.turn);
    return refreshCards();
  },
  // A retried call starts over: the words and the thinking the failed attempt streamed are removed,
  // so neither line holds both attempts'.
  "inference-retried": ({ turn }) => {
    claim(turn);
    if (live.bubble) live.bubble.remove();
    if (live.thinking) live.thinking.remove();
    live.bubble = null;
    live.thinking = null;
    live.streamed = false;
  },
  // Says that an answer happened and which turn it ended, and carries no words, on any provider.
  answered: ({ turn }) => ended(turn, null, true),
  "turn-failed": ({ turn }) => ended(turn, "the agent could not answer", true),
  "turn-refused": ({ turn }) => ended(turn, "the agent declined to answer", true),
  "turn-stopped": ({ turn, reason }) => ended(turn, "the agent stopped the turn: " + reason, false),
  terminated: () => {
    follow(null);
    setWorking(false);
  },
};

// Opens the stream for the current conversation, and reads the state each time it opens.
function listen() {
  if (events) events.close();
  const source = new EventSource(`/api/agents/${agentId}/events`);
  events = source;
  // Whether this connection has received an event, and so whether the browser will hand its id
  // back, and the server replay what came after it, when the connection is restored.
  let heard = false;
  // A read that fails leaves the screen unreconciled, so this stream is closed and a new one,
  // which replays nothing, is opened after 3 seconds, and catches up when it opens. The error still
  // reaches the chain, which logs it.
  source.onopen = () => {
    const replays = heard;
    queue(() =>
      reconcile(replays).catch((error) => {
        if (events === source) reopenLater(source);
        throw error;
      }),
    );
  };
  for (const name of EVENT_NAMES) {
    source.addEventListener(name, (e) => {
      heard = true;
      const handler = handlers[name];
      if (handler) queue(() => handler(JSON.parse(e.data)));
    });
  }
  source.onerror = () => {
    // While the stream is connecting the browser reconnects on its own, and onopen reads the
    // state when it does. A response that is not an event stream (a proxy's 502 or 503, a 500
    // while the database is down) closes the stream for good, and nothing would reopen it: so the
    // page opens a new one after 3 seconds, if this is still the stream it is listening to.
    if (source.readyState === EventSource.CLOSED) reopenLater(source);
  };
}

// Closes this stream and opens a new one after 3 seconds, unless the page has moved on to another
// stream by then: asked twice for one stream, the second asking finds it replaced already.
function reopenLater(source) {
  source.close();
  setTimeout(() => {
    if (events === source) listen();
  }, 3000);
}

async function send(event) {
  event.preventDefault();
  const text = textInput.value.trim();
  if (!text) return;
  textInput.value = "";
  recall = null;
  saveDraft();
  fitBox();
  focusBox(form);
  const mine = generation;
  // Drawn as typed, and pending until a turn takes it up. After a reload the story shows what the
  // agent was given, and messages sent while it was busy are one line there, joined. Sending
  // brings the view back to the newest line.
  following = true;
  const typed = appendLine("user", text, null);
  pendingTyped.push(typed);
  // 202 and an empty body: the line is now the agent's problem. What the agent does with it
  // arrives on the stream this page is already listening to, and the answer's words with it only
  // from a provider that streams; the state holds them either way. Sent while a turn is in
  // progress, it is accepted all the same and answered with whatever else arrived meanwhile.
  let response = null;
  try {
    response = await fetch(`/api/agents/${agentId}/messages`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ text }),
    });
  } catch (networkError) {
    response = null;
  }
  if (mine !== generation || (response !== null && response.ok)) return;
  // Not accepted, so no turn will take it up.
  pendingTyped = pendingTyped.filter((line) => line !== typed);
  appendLine(
    "system",
    response !== null && response.status === 409
      ? "this conversation has been terminated; start a new chat"
      : "that message did not go through",
    null,
  );
}

// A card: what will be done, the tool and how long is left, the arguments, and the two answers.
function renderApproval(card) {
  if (!card.id || approvalsSection.querySelector(`[data-call="${card.id}"]`)) return;
  const div = document.createElement("div");
  div.className = "approval-card";
  div.dataset.call = card.id;
  div.setAttribute("role", "group");
  div.setAttribute("aria-label", card.what || card.tool);
  const title = document.createElement("div");
  title.className = "approval-what";
  title.textContent = card.what || card.tool;
  const meta = document.createElement("div");
  meta.className = "approval-meta";
  const tool = document.createElement("span");
  tool.className = "approval-tool";
  tool.textContent = card.tool;
  meta.appendChild(tool);
  const deadline = Date.parse(card.deadline);
  if (!Number.isNaN(deadline)) {
    const clock = document.createElement("span");
    clock.setAttribute("role", "timer");
    clock.dataset.deadline = String(deadline);
    meta.appendChild(clock);
  }
  const args = document.createElement("pre");
  args.textContent = card.args ?? "";
  const actions = document.createElement("div");
  actions.className = "approval-actions";
  const allow = document.createElement("button");
  allow.type = "button";
  allow.className = "primary";
  allow.textContent = "Approve";
  allow.addEventListener("click", () => decide(card, "approve", div));
  const deny = document.createElement("button");
  deny.type = "button";
  deny.textContent = "Deny";
  deny.addEventListener("click", () => decide(card, "deny", div));
  actions.append(allow, deny);
  div.append(title, meta, args, actions);
  approvalsSection.appendChild(div);
}

async function decide(card, decision, div) {
  const mine = generation;
  div.querySelectorAll("button").forEach((b) => (b.disabled = true));
  let response = null;
  try {
    response = await fetch(`/api/agents/${agentId}/approvals/${card.id}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ decision, note: decision === "deny" ? "denied from the page" : "" }),
      signal: AbortSignal.timeout(10000),
    });
  } catch (networkError) {
    response = null;
  }
  if (mine !== generation) return;
  if (response === null || (response.status !== 202 && response.status !== 409)) {
    // The answer did not go through and the card is still waiting, so the buttons come back. A
    // 400 is a refusal and trying again will not help; anything else may.
    div.querySelectorAll("button").forEach((b) => (b.disabled = false));
    appendLine(
      "system",
      response !== null && response.status === 400
        ? "your answer was refused"
        : "your answer did not go through; try again",
      null,
    );
    return;
  }
  if (response.status === 202) {
    // A note on the turn the card was asked in, which stays after that turn's request line for
    // the tool when the turn is drawn again from the state.
    const said = decision === "approve" ? "you approved it" : "you denied it";
    const note = appendLine("system", said, card.turn);
    note.dataset.tool = card.tool;
  }
  // 202 or 409 (another tab answered first, or the term ran out), the cards are redrawn from what
  // is waiting now rather than from the click.
  queue(refreshCards);
  focusBox(div);
}

// Clears what is on screen: a new conversation starts from nothing.
function clearScreen() {
  log.replaceChildren();
  approvalsSection.replaceChildren();
  follow(null);
  pendingTyped = [];
  toolByCall = new Map();
  toolByKey = new Map();
  drawnTurns = new Set();
  setWorking(false);
  tickDeadlines();
  following = true;
  keepView();
}

// "New chat" terminates this conversation's agent and starts another conversation. The old agent
// is terminated, so it takes no more input and is not left waiting; what it said is kept. The old
// stream is closed first and its queued work is dropped, so nothing of the old conversation draws
// into the new one. What is typed in the box stays there, for the new conversation.
async function startNewChat() {
  const finished = agentId;
  if (events) events.close();
  generation += 1;
  forgetDraft();
  useAgent(crypto.randomUUID());
  saveDraft();
  recall = null;
  clearScreen();
  listen();
  // After the switch, deliberately: the new conversation should open even if this fails, and an
  // ending the server never heard is a leaked agent, not a broken page.
  try {
    await fetch(`/api/agents/${finished}`, { method: "DELETE" });
  } catch (ignored) {
    // Nothing to tell the person: their new chat is already open and working.
  }
}

// The message box. Enter sends and Shift+Enter starts a new line. The box grows with what is typed,
// up to the six lines the stylesheet allows, and then scrolls.

// Puts the cursor in the message box. `from` is the control the person just used, if any: the box
// takes the focus from it or from nothing, but never from another control the person has moved to,
// and never while they are selecting text in the conversation.
function focusBox(from = null) {
  const active = document.activeElement;
  const free =
    !active ||
    active === document.body ||
    active === textInput ||
    (from !== null && from.contains(active));
  if (!free) return;
  const selection = document.getSelection();
  if (selection && !selection.isCollapsed && log.contains(selection.anchorNode)) return;
  textInput.focus();
}

// Makes the box as tall as its text; the stylesheet's max-height caps it.
function fitBox() {
  textInput.style.height = "auto";
  const border = textInput.offsetHeight - textInput.clientHeight;
  textInput.style.height = textInput.scrollHeight + border + "px";
}

// What is typed and not sent yet is kept for this tab, per conversation, so a reload does not lose
// it. Storage may be missing or refuse (a private window, blocked site data); the page works
// without it, and the draft is then not kept.
function draftKey() {
  return "draft:" + agentId;
}

function saveDraft() {
  try {
    if (textInput.value) sessionStorage.setItem(draftKey(), textInput.value);
    else sessionStorage.removeItem(draftKey());
  } catch (unavailable) {
    // Not kept.
  }
}

function forgetDraft() {
  try {
    sessionStorage.removeItem(draftKey());
  } catch (unavailable) {
    // Nothing was kept.
  }
}

function restoreDraft() {
  try {
    textInput.value = sessionStorage.getItem(draftKey()) ?? "";
  } catch (unavailable) {
    textInput.value = "";
  }
  fitBox();
}

// What the person said in this conversation, newest first, as the screen shows it. Lines sent
// while the agent was busy are one line once the story joins them.
function earlierMessages() {
  const said = [];
  for (const line of [...log.children].reverse()) {
    if (line.classList.contains("user") && line.textContent !== said[said.length - 1]) {
      said.push(line.textContent);
    }
  }
  return said;
}

function showInBox(text) {
  textInput.value = text;
  textInput.setSelectionRange(text.length, text.length);
  fitBox();
}

// One message further back; at the oldest, it stays there. False when there is nothing to recall.
function recallOlder() {
  if (recall === null) {
    const said = earlierMessages();
    if (said.length === 0) return false;
    recall = { said, at: -1, draft: textInput.value };
  }
  if (recall.at + 1 < recall.said.length) {
    recall.at += 1;
    showInBox(recall.said[recall.at]);
  }
  return true;
}

// One message forward; past the newest, the draft that was in the box comes back.
function recallNewer() {
  recall.at -= 1;
  if (recall.at >= 0) {
    showInBox(recall.said[recall.at]);
  } else {
    const draft = recall.draft;
    recall = null;
    showInBox(draft);
  }
}

function caretOnFirstLine() {
  return !textInput.value.slice(0, textInput.selectionStart).includes("\n");
}

function caretOnLastLine() {
  return !textInput.value.slice(textInput.selectionEnd).includes("\n");
}

textInput.addEventListener("keydown", (event) => {
  // While an input method composes a word, Enter and the arrows belong to it.
  if (event.isComposing || event.keyCode === 229) return;
  if (event.key === "Enter" && !event.shiftKey) {
    event.preventDefault();
    form.requestSubmit();
  } else if (event.key === "ArrowUp" && caretOnFirstLine()) {
    if (recallOlder()) event.preventDefault();
  } else if (event.key === "ArrowDown" && recall !== null && caretOnLastLine()) {
    event.preventDefault();
    recallNewer();
  } else if (event.key === "Escape") {
    recall = null;
    showInBox("");
    saveDraft();
  }
});

// Typing ends a recall: what is in the box is now the person's draft.
textInput.addEventListener("input", () => {
  recall = null;
  saveDraft();
  fitBox();
});

form.addEventListener("submit", send);

// A conversation with something in it has its agent terminated only once the person confirms.
// Either way the cursor goes back to the box.
newChatButton.addEventListener("click", () => {
  if (log.children.length === 0) {
    startNewChat();
    focusBox(newChatButton);
    return;
  }
  confirmNew.returnValue = "";
  confirmNew.showModal();
});

confirmNew.addEventListener("close", () => {
  if (confirmNew.returnValue === "new") startNewChat();
  focusBox(newChatButton);
});

// Where the person scrolls decides whether the view follows new content.
log.addEventListener("scroll", () => {
  following = atBottom();
  jumpButton.hidden = following;
});

jumpButton.addEventListener("click", () => {
  following = true;
  keepView();
  focusBox(jumpButton);
});

useAgent(agentId);
restoreDraft();
listen();
focusBox();
