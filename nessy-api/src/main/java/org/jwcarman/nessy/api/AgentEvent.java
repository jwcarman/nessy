/*
 * Copyright © 2026 James Carman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jwcarman.nessy.api;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Instant;
import java.util.Objects;
import org.jwcarman.nessy.api.message.AnswerMessage;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * The live story of an agent's turns, for whoever is watching them happen.
 *
 * <p>A TURN is one observation processed start to finish — the model may be called several times
 * along the way, asking for tools and being asked again, and all of that is the middle of one turn.
 * That is the sense the LLM world uses when it says "multi-turn": one exchange, not one message.
 *
 * <p><b>Narration, not record.</b> None of these ever folds into what the agent remembers — that is
 * {@code Memory}'s job, and it speaks messages.
 *
 * <p><b>No raw tool arguments appear anywhere.</b> Narration is serialized once per event and
 * delivered to a browser, and a tool's arguments may hold credentials or personal data. What a
 * watcher gets instead is the binding's {@code ToolDescriber} output — the sentence a person can
 * read, which is what a UI wanted to render anyway.
 *
 * <p><b>Every event carries an id</b> — a UUIDv7, so it is time-ordered as well as unique. That
 * makes it usable directly as an SSE {@code id:} and therefore as the {@code Last-Event-ID} cursor
 * a reconnecting browser sends. Because the id embeds a timestamp, a turn's duration is the
 * distance between its {@link TurnStarted} and {@link TurnEnded} ids — no extra field needed.
 *
 * <p><b>Narration is at-least-once, and the id does not change that.</b> A retried segment can
 * narrate the same thing twice, and because ids are minted at emit the two copies carry DIFFERENT
 * ids. Dedupe by the event's natural key; the id identifies a delivery, not a fact.
 *
 * <p>Sealed-grammar etiquette: core switches over this type are exhaustive with no {@code default}
 * arm. Rather than writing one, extend {@link AgentSubscriberAdapter} or compose through {@link
 * AgentSubscriber#of} — both stay silent on variants you did not ask for, and both inherit a no-op
 * for free when the grammar grows.
 */
/** Wire names are a compatibility surface: an SSE stream's event names come from here. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = AgentEvent.TurnStarted.class, name = "turn-started"),
  @JsonSubTypes.Type(value = AgentEvent.TextDelta.class, name = "text-delta"),
  @JsonSubTypes.Type(value = AgentEvent.ThinkingDelta.class, name = "thinking-delta"),
  @JsonSubTypes.Type(value = AgentEvent.RedactedThinking.class, name = "redacted-thinking"),
  @JsonSubTypes.Type(value = AgentEvent.ToolCallRequested.class, name = "tool-call-requested"),
  @JsonSubTypes.Type(value = AgentEvent.ApprovalRequested.class, name = "approval-requested"),
  @JsonSubTypes.Type(value = AgentEvent.ApprovalDecided.class, name = "approval-decided"),
  @JsonSubTypes.Type(value = AgentEvent.ToolCallCompleted.class, name = "tool-call-completed"),
  @JsonSubTypes.Type(value = AgentEvent.AssistantSaid.class, name = "assistant-said"),
  @JsonSubTypes.Type(value = AgentEvent.TurnEnded.class, name = "turn-ended")
})
public sealed interface AgentEvent {

  String ID_MUST_NOT_BE_NULL = "id must not be null";
  String CALL_ID_MUST_NOT_BE_NULL = "callId must not be null";
  String TOOL_NAME_MUST_NOT_BE_NULL = "toolName must not be null";
  String DESCRIPTION_MUST_NOT_BE_NULL = "description must not be null";

  /** This event's own id: a UUIDv7, unique and time-ordered. */
  String id();

  /**
   * Work has begun on an observation.
   *
   * <p>Exists because the first thing a watcher shows is that something is happening, and a turn
   * that opens with a tool call would otherwise narrate nothing for seconds.
   */
  record TurnStarted(String id) implements AgentEvent {
    public TurnStarted {
      Objects.requireNonNull(id, ID_MUST_NOT_BE_NULL);
    }
  }

  /** A chunk of assistant prose arrived from the stream. */
  record TextDelta(String id, String text) implements AgentEvent {
    public TextDelta {
      Objects.requireNonNull(id, ID_MUST_NOT_BE_NULL);
      Objects.requireNonNull(text, "text must not be null");
    }
  }

  /** A chunk of the model's visible reasoning arrived from the stream. */
  record ThinkingDelta(String id, String text) implements AgentEvent {
    public ThinkingDelta {
      Objects.requireNonNull(id, ID_MUST_NOT_BE_NULL);
      Objects.requireNonNull(text, "text must not be null");
    }
  }

  /** A complete redacted-thinking block arrived; its contents are opaque by design. */
  record RedactedThinking(String id, String data) implements AgentEvent {
    public RedactedThinking {
      Objects.requireNonNull(id, ID_MUST_NOT_BE_NULL);
      Objects.requireNonNull(data, "data must not be null");
    }
  }

  /**
   * The model asked for a tool.
   *
   * <p>{@code description} is the binding's {@code ToolDescriber} rendering of this call — "search
   * orders for blue widgets" — and it is all a watcher gets. The arguments themselves never
   * narrate.
   */
  record ToolCallRequested(String id, String callId, String toolName, String description)
      implements AgentEvent {
    public ToolCallRequested {
      Objects.requireNonNull(id, ID_MUST_NOT_BE_NULL);
      Objects.requireNonNull(callId, CALL_ID_MUST_NOT_BE_NULL);
      Objects.requireNonNull(toolName, TOOL_NAME_MUST_NOT_BE_NULL);
      Objects.requireNonNull(description, DESCRIPTION_MUST_NOT_BE_NULL);
    }
  }

  /**
   * A person is being waited on — the only event a watcher can ACT on.
   *
   * <p>Fires when an approver defers rather than deciding. Without it a UI shows "using tool X" and
   * then silence, possibly for days, while the very person watching is what it is waiting for.
   *
   * <p>This is a deliberate reversal of the older rule that parking is never narrated. That rule
   * was written for an unattended agent; it does not survive a chat interface.
   *
   * @param description what the person is being asked to allow — what the approve button is about
   * @param expiresAt when the question stops standing, so a UI can show urgency and stop offering a
   *     button that would no longer be honoured
   */
  record ApprovalRequested(
      String id, String callId, String toolName, String description, Instant expiresAt)
      implements AgentEvent {
    public ApprovalRequested {
      Objects.requireNonNull(id, ID_MUST_NOT_BE_NULL);
      Objects.requireNonNull(callId, CALL_ID_MUST_NOT_BE_NULL);
      Objects.requireNonNull(toolName, TOOL_NAME_MUST_NOT_BE_NULL);
      Objects.requireNonNull(description, DESCRIPTION_MUST_NOT_BE_NULL);
      Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }
  }

  /**
   * The approver's answer for one call: approved, or denied with a reason.
   *
   * <p>Pairs with {@link ApprovalRequested}: a question was put to a person, and this is what they
   * said.
   *
   * <p><b>WHEN this fires is not yet decided.</b> Every binding carries an approver — an ungated
   * tool uses {@code Approver.always()} — so narrating every decision would put a line in a UI for
   * every tool call forever. The candidates: only when a person decided (simple, but an immediate
   * policy denial then narrates nothing and is only visible as a failed call), or on any denial
   * plus any decision a person made (more informative, compound rule). Settle it before an engine
   * implements against either.
   */
  record ApprovalDecided(String id, String callId, String toolName, ApprovalResult result)
      implements AgentEvent {
    public ApprovalDecided {
      Objects.requireNonNull(id, ID_MUST_NOT_BE_NULL);
      Objects.requireNonNull(callId, CALL_ID_MUST_NOT_BE_NULL);
      Objects.requireNonNull(toolName, TOOL_NAME_MUST_NOT_BE_NULL);
      Objects.requireNonNull(result, "result must not be null");
    }
  }

  /** One tool call settled — an answer in hand, or a failure. */
  record ToolCallCompleted(String id, String callId, String toolName, ToolResult result)
      implements AgentEvent {
    public ToolCallCompleted {
      Objects.requireNonNull(id, ID_MUST_NOT_BE_NULL);
      Objects.requireNonNull(callId, CALL_ID_MUST_NOT_BE_NULL);
      Objects.requireNonNull(toolName, TOOL_NAME_MUST_NOT_BE_NULL);
      Objects.requireNonNull(result, "result must not be null");
    }
  }

  /**
   * A settled assistant message — the deltas were the preview, this is the sentence.
   *
   * <p>Fires once per model reply, so a turn that used tools emits SEVERAL: the reply asking for
   * tools is still the model saying something. A watcher wanting prose alone filters for text.
   */
  record AssistantSaid(String id, AnswerMessage message) implements AgentEvent {
    public AssistantSaid {
      Objects.requireNonNull(id, ID_MUST_NOT_BE_NULL);
      Objects.requireNonNull(message, "message must not be null");
    }
  }

  /**
   * The turn's closing line — the only place a refusal or a failure reaches a watcher, and where
   * what the turn cost is reported.
   */
  record TurnEnded(String id, TurnResult outcome, Usage usage) implements AgentEvent {
    public TurnEnded {
      Objects.requireNonNull(id, ID_MUST_NOT_BE_NULL);
      Objects.requireNonNull(outcome, "outcome must not be null");
      Objects.requireNonNull(usage, "usage must not be null");
    }
  }
}
