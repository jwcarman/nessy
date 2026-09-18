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
package org.jwcarman.nessy.engine.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.engine.agent.AgentEffect;
import org.jwcarman.nessy.engine.agent.AgentState;
import org.jwcarman.nessy.engine.history.HistoryEntry;

/**
 * What a committed fold is worth announcing.
 *
 * <p>Derived from the entries the fold wrote rather than from the transition it made, because an
 * entry is a fact and a transition is a mechanism. A watcher wants to hear that a call was
 * approved, not that the agent moved from one arm of a sealed interface to another -- and deriving
 * from entries means the narration cannot claim something the story does not also say.
 *
 * <p><b>Not every entry is announced and not every announcement is an entry.</b> The mapping is
 * deliberately partial in both directions: {@code Thinking} has no entry at all, and a grant is
 * announced under a name a person reads rather than the one the table uses. The switch is
 * exhaustive so that a new entry has to be considered, not so that every one has to be said.
 */
final class Narrations {

  private Narrations() {}

  /**
   * Everything worth saying about one fold, in the order it happened.
   *
   * <p>The opening comes first when there is one, because a turn starting is the first thing a
   * watcher wants and the fold that closes a turn while opening the next writes the closing entry
   * first.
   */
  static List<AgentEvent> of(
      List<HistoryEntry> recorded,
      AgentEvent.TurnStarted opening,
      List<AgentEffect> effects,
      AgentState<?> next) {
    List<AgentEvent> events = new ArrayList<>();
    for (HistoryEntry entry : recorded) {
      // Read off the block rather than inferred from the entry it sits in. Commentary can
      // arrive in a message that made no calls at all, and position would file that as the
      // answer -- which is the mistake the block kind exists to make impossible.
      commentary(entry).ifPresent(said -> events.add(new AgentEvent.Commentary(said)));
      of(entry).ifPresent(events::add);
      // However it ended, it ended: the one event to hear when the story grew by a turn.
      switch (entry) {
        case HistoryEntry.InferenceAnswered answered ->
            events.add(new AgentEvent.TurnEnded(answered.turn()));
        case HistoryEntry.InferenceFailed failed ->
            events.add(new AgentEvent.TurnEnded(failed.turn()));
        case HistoryEntry.InferenceRefused refused ->
            events.add(new AgentEvent.TurnEnded(refused.turn()));
        default -> {
          // Not the end of a turn.
        }
      }
    }
    // After whatever closed the previous turn, because one fold can do both and the closing
    // entry is written first. Not derived from an entry like the rest: the observation is
    // rendered by the store, which is the only thing that holds a renderer, so the fold hands
    // this in instead.
    if (opening != null) {
      events.add(opening);
    }
    // Read off the effect rather than the state. `Inferring` is where an agent sits for the
    // whole of a call, so a fold that stays there without emitting anything -- an observation
    // queued mid-turn -- would announce a second "thinking" for a call already in flight. The
    // effect is emitted exactly once per call, which is what this means.
    if (effects.stream().anyMatch(AgentEffect.Infer.class::isInstance)) {
      events.add(new AgentEvent.Thinking());
    }
    if (next instanceof AgentState.Terminated<?>) {
      events.add(new AgentEvent.Terminated());
    }
    return events;
  }

  /** What the model said while working, if it said anything. */
  private static Optional<String> commentary(HistoryEntry entry) {
    List<? extends Block> blocks =
        switch (entry) {
          case HistoryEntry.InferenceRequestedActions request -> request.blocks();
          case HistoryEntry.InferenceAnswered answer -> answer.blocks();
          default -> List.of();
        };
    String said =
        blocks.stream()
            .filter(Block.Commentary.class::isInstance)
            .map(block -> ((Block.Commentary) block).text())
            .reduce("", String::concat);
    return said.isEmpty() ? Optional.empty() : Optional.of(said);
  }

  private static Optional<AgentEvent> of(HistoryEntry entry) {
    return Optional.ofNullable(
        switch (entry) {
          case HistoryEntry.ObservationReceived(_, TurnId turn, var blocks) ->
              new AgentEvent.TurnStarted(turn, text(blocks));
          case HistoryEntry.InferenceAnswered(_, _, var blocks) ->
              new AgentEvent.Answered(text(blocks));
          case HistoryEntry.InferenceFailed _ -> new AgentEvent.TurnFailed();
          case HistoryEntry.InferenceRefused _ -> new AgentEvent.TurnRefused();
          case HistoryEntry.InferenceRequestedActions request ->
              new AgentEvent.ActionsRequested(
                  request.calls().stream().map(Block.ToolCall::name).toList());
          // Announced by the name a person uses, not the one the table does.
          case HistoryEntry.ToolApproved(_, _, CallId callId, _) ->
              new AgentEvent.CallApproved(callId);
          case HistoryEntry.ToolDenied(_, _, CallId callId, String reason, _) ->
              new AgentEvent.CallDenied(callId, reason);
          case HistoryEntry.ToolSucceeded(_, _, CallId callId, _) ->
              new AgentEvent.CallFinished(callId);
          case HistoryEntry.ToolFailed(_, _, CallId callId, String message) ->
              new AgentEvent.CallFailed(callId, message);
        });
  }

  /**
   * The readable part of a run of blocks.
   *
   * <p>Text only, and joined: a watcher wants what a person would read, not the grammar. A turn
   * whose content is entirely non-text narrates an empty string rather than nothing, because the
   * event still says the turn ended.
   */
  private static String text(List<? extends Block> blocks) {
    return blocks.stream()
        .filter(Block.Text.class::isInstance)
        .map(block -> ((Block.Text) block).text())
        .reduce("", String::concat);
  }
}
