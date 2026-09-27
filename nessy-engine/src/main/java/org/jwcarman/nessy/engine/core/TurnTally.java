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
package org.jwcarman.nessy.engine.core;

import java.time.Instant;
import java.util.List;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.TurnStats;
import org.jwcarman.nessy.backend.event.AgentEvent;

/**
 * What a turn has done, worked out from what it did.
 *
 * <p><b>One place, because two things count.</b> The fold keeps a running tally in the state it
 * rebuilds, and a door handing an answer back has to say what the whole turn cost after that state
 * has already moved on to idle. Both are the same arithmetic over the same events, and an
 * accounting written twice is an accounting that will eventually disagree with itself -- which is
 * the one thing a number people reconcile against a bill must never do.
 *
 * <p>Not public API. {@link TurnStats} is what anyone outside reads; this is how it is filled in.
 */
public final class TurnTally {

  private TurnTally() {}

  /**
   * The tally, moved on by one event.
   *
   * <p>Every event that cost something is here, and nothing else moves a count. An arm that never
   * involved the model -- a tool being approved, denied or finished -- leaves the tally alone: the
   * calls it asked for were counted when the model asked for them, because that is the moment they
   * were paid for.
   */
  public static TurnStats after(TurnStats stats, AgentEvent event) {
    return switch (event) {
      case AgentEvent.InferenceAnswered answered -> stats.answered(answered.usage());
      case AgentEvent.InferenceRefused refused -> stats.answered(refused.usage());
      case AgentEvent.InferenceFailed failed -> stats.failed(failed.usage());
      case AgentEvent.InferenceAttempted attempted -> stats.failed(attempted.usage());
      case AgentEvent.ActionsRequested requested ->
          stats.requestedActions(requested.actions().size(), requested.usage());
      // A turn ended on purpose spent nothing on ending: the decision cost no call, and what the
      // turn did spend is already on the events that spent it.
      case AgentEvent.TurnFailed _,
          AgentEvent.TurnStarted _,
          AgentEvent.ToolApproved _,
          AgentEvent.ToolDenied _,
          AgentEvent.ToolSucceeded _,
          AgentEvent.ToolFailed _,
          AgentEvent.Terminated _ ->
          stats;
    };
  }

  /**
   * The whole tally of one turn, read back off the story.
   *
   * <p>For a door answering a caller once the turn has closed and the state it was accumulated in
   * is gone. Scoped to one turn's id rather than to a position in the stream: an agent's story
   * holds every turn it has ever taken.
   *
   * @throws IllegalArgumentException if that turn never opened, which would otherwise be reported
   *     as a turn that cost nothing
   */
  public static TurnStats of(List<AgentEvent> story, TurnId turn) {
    Instant opened =
        story.stream()
            .filter(AgentEvent.TurnStarted.class::isInstance)
            .map(AgentEvent.TurnStarted.class::cast)
            .filter(started -> started.turn().equals(turn))
            .map(AgentEvent.TurnStarted::startedAt)
            .findFirst()
            .orElseThrow(
                () -> new IllegalArgumentException("no turn " + turn + " in this agent's story"));
    TurnStats stats = TurnStats.opened(opened);
    for (AgentEvent event : story) {
      if (inTurn(event, turn)) {
        stats = after(stats, event);
      }
    }
    return stats;
  }

  private static boolean inTurn(AgentEvent event, TurnId turn) {
    return switch (event) {
      case AgentEvent.TurnStarted started -> started.turn().equals(turn);
      case AgentEvent.InferenceAnswered answered -> answered.turn().equals(turn);
      case AgentEvent.InferenceRefused refused -> refused.turn().equals(turn);
      case AgentEvent.InferenceFailed failed -> failed.turn().equals(turn);
      case AgentEvent.InferenceAttempted attempted -> attempted.turn().equals(turn);
      case AgentEvent.ActionsRequested requested -> requested.turn().equals(turn);
      case AgentEvent.ToolApproved approved -> approved.turn().equals(turn);
      case AgentEvent.ToolDenied denied -> denied.turn().equals(turn);
      case AgentEvent.ToolSucceeded succeeded -> succeeded.turn().equals(turn);
      case AgentEvent.ToolFailed failed -> failed.turn().equals(turn);
      case AgentEvent.TurnFailed ended -> ended.turn().equals(turn);
      // Between turns, belonging to the agent's life rather than to any one of them.
      case AgentEvent.Terminated _ -> false;
    };
  }
}
