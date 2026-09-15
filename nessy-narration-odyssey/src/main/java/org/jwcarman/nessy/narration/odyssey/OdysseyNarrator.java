package org.jwcarman.nessy.narration.odyssey;

import java.util.Objects;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.spi.narration.Narrator;

/**
 * Journals every event about every agent to that agent's stream.
 *
 * <p>Narration is best-effort by the engine's contract; written to a journal it also becomes
 * <em>resumable</em>: a page that was closed asks for everything after the last event it saw and
 * gets it, which no fan-out held in memory can offer.
 *
 * <p><b>What is written.</b> The event itself, as its JSON: a {@code type} naming the kind, then
 * its fields as Jackson sees the record -- a {@code CallId} is its string, a {@code TurnId} its
 * number. The SSE event name is that same kind, so a browser can listen for {@code content-delta}
 * and a subscriber with a mapper of its own gets the event back typed.
 */
public class OdysseyNarrator implements Narrator {

  private final AgentStreams streams;

  public OdysseyNarrator(AgentStreams streams) {
    this.streams = Objects.requireNonNull(streams, "streams must not be null");
  }

  @Override
  public void narrate(AgentType agentType, AgentId agentId, AgentEvent event) {
    streams.stream(agentType, agentId).publish(nameOf(event), event);
  }

  /**
   * The event name on the wire: the kind the event declares in its own JSON. Spelled out here
   * rather than read off the annotation on every event; a test holds the two together.
   */
  public static String nameOf(AgentEvent event) {
    return switch (event) {
      case AgentEvent.TurnStarted _ -> "turn-started";
      case AgentEvent.Thinking _ -> "thinking";
      case AgentEvent.Answered _ -> "answered";
      case AgentEvent.TurnFailed _ -> "turn-failed";
      case AgentEvent.TurnRefused _ -> "turn-refused";
      case AgentEvent.Commentary _ -> "commentary";
      case AgentEvent.ActionsRequested _ -> "actions-requested";
      case AgentEvent.CallApproved _ -> "call-approved";
      case AgentEvent.CallDenied _ -> "call-denied";
      case AgentEvent.CallFinished _ -> "call-finished";
      case AgentEvent.CallFailed _ -> "call-failed";
      case AgentEvent.Terminated _ -> "terminated";
      case AgentEvent.ApprovalSought _ -> "approval-sought";
      case AgentEvent.ApprovalDeferred _ -> "approval-deferred";
      case AgentEvent.CallDeferred _ -> "call-deferred";
      case AgentEvent.ThinkingDelta _ -> "thinking-delta";
      case AgentEvent.ContentDelta _ -> "content-delta";
    };
  }
}
