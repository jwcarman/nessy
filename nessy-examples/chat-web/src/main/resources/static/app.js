"use strict";

const log = document.getElementById("log");
const approvalsSection = document.getElementById("approvals");
const form = document.getElementById("send-form");
const textInput = document.getElementById("text");
const sendButton = document.getElementById("send");
const newConversationButton = document.getElementById("new-conversation");

let conversationId = location.hash.slice(1) || localStorage.getItem("conversationId") || crypto.randomUUID();
persistConversationId(conversationId);

let openBubble = null;
let openThinkingLine = null;
const toolLines = new Map();

function persistConversationId(id) {
  conversationId = id;
  location.hash = id;
  localStorage.setItem("conversationId", id);
}

function setInputDisabled(disabled) {
  textInput.disabled = disabled;
  sendButton.disabled = disabled;
}

function appendLine(role, text) {
  const div = document.createElement("div");
  div.className = "line " + role;
  div.textContent = text;
  log.appendChild(div);
  log.scrollTop = log.scrollHeight;
  return div;
}

function appendSystemLine(text) {
  return appendLine("system", text);
}

function appendToolLine(id, text) {
  const div = appendLine("tool", "🔧 " + text);
  toolLines.set(id, div);
  return div;
}

function closeThinkingLine() {
  openThinkingLine = null;
}

function renderApprovalCard(card) {
  const div = document.createElement("div");
  div.className = "approval-card";
  div.dataset.token = card.token;
  const title = document.createElement("div");
  title.className = "approval-tool";
  title.textContent = card.tool;
  const args = document.createElement("pre");
  args.textContent = card.args;
  const actions = document.createElement("div");
  actions.className = "approval-actions";
  const allow = document.createElement("button");
  allow.type = "button";
  allow.textContent = "Approve";
  allow.addEventListener("click", () => decide(card.token, "allow", div));
  const deny = document.createElement("button");
  deny.type = "button";
  deny.textContent = "Deny";
  deny.addEventListener("click", () => decide(card.token, "deny", div));
  actions.append(allow, deny);
  div.append(title, args, actions);
  approvalsSection.appendChild(div);
}

async function stream(response, handlers) {
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let sep;
    while ((sep = buffer.indexOf("\n\n")) >= 0) {
      const chunk = buffer.slice(0, sep);
      buffer = buffer.slice(sep + 2);
      let event = "message", data = "";
      for (const line of chunk.split("\n")) {
        if (line.startsWith("event:")) event = line.slice(6).trim();
        else if (line.startsWith("data:")) data += line.slice(5).trim();
      }
      handlers[event]?.(data ? JSON.parse(data) : {});
    }
  }
}

function turnHandlers() {
  return {
    delta(payload) {
      closeThinkingLine();
      if (!openBubble) {
        openBubble = appendLine("assistant", "");
      }
      openBubble.textContent += payload.text;
      log.scrollTop = log.scrollHeight;
    },
    thinking(payload) {
      if (!openThinkingLine) {
        openThinkingLine = appendLine("thinking", "");
      }
      openThinkingLine.textContent += payload.text;
      log.scrollTop = log.scrollHeight;
    },
    "tool-requested": (payload) => {
      closeThinkingLine();
      appendToolLine(payload.id, "requested " + payload.name);
    },
    "tool-progress": (payload) => {
      closeThinkingLine();
      const div = toolLines.get(payload.id);
      if (div) div.textContent = "🔧 " + payload.message;
    },
    "tool-decided": (payload) => {
      closeThinkingLine();
      const div = toolLines.get(payload.id);
      if (div) div.textContent += payload.allowed ? " — allowed" : " — denied";
    },
    "tool-completed": (payload) => {
      closeThinkingLine();
      const div = toolLines.get(payload.id);
      if (div) div.textContent += payload.error ? " — failed" : " — done";
    },
    "approval-needed": (payload) => {
      closeThinkingLine();
      renderApprovalCard(payload);
    },
    done(payload) {
      closeThinkingLine();
      openBubble = null;
      toolLines.clear();
      setInputDisabled(false);
      if (payload.status === "FAILED") {
        appendSystemLine(payload.failureReason ?? "the turn failed");
      }
    },
  };
}

async function decide(token, decision, cardElement) {
  const response = await fetch(`/api/approvals/${token}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ decision }),
  });
  cardElement.remove();
  if (response.status === 409) {
    await load();
    return;
  }
  setInputDisabled(true);
  await stream(response, turnHandlers());
}

async function send(event) {
  event.preventDefault();
  const text = textInput.value.trim();
  if (!text) return;
  textInput.value = "";
  appendLine("user", text);
  setInputDisabled(true);
  const response = await fetch(`/api/conversations/${conversationId}/messages`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ text }),
  });
  await stream(response, turnHandlers());
}

async function load() {
  log.innerHTML = "";
  approvalsSection.innerHTML = "";
  openBubble = null;
  openThinkingLine = null;
  toolLines.clear();
  const response = await fetch(`/api/conversations/${conversationId}`);
  const state = await response.json();
  for (const line of state.transcript) {
    appendLine(line.role, line.text);
  }
  for (const card of state.approvals) {
    renderApprovalCard(card);
  }
  const busy = state.status === "AWAITING_MODEL" || state.status === "EXECUTING_TOOL";
  setInputDisabled(busy);
}

form.addEventListener("submit", send);

newConversationButton.addEventListener("click", () => {
  persistConversationId(crypto.randomUUID());
  load();
});

load();
