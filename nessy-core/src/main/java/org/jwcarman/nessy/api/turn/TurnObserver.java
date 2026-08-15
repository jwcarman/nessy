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
package org.jwcarman.nessy.api.turn;

import java.util.Objects;
import java.util.function.Supplier;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.slf4j.Logger;

/**
 * Whoever is sitting there, watching this turn happen — a REPL painting deltas, a UI narrating
 * homework. Bound per entry: the observer handed to {@code tell} or {@code resume} sees the segment
 * that call starts, and nothing after a park. The consumer may not exist at all — an autonomous
 * agent runs every turn against {@link #noop()} and loses nothing.
 *
 * <p>Implement directly (a lambda) when one concern covers every event; extend {@link
 * TurnObserverAdapter} to override per-variant hooks and ignore the rest; or compose one from
 * per-variant lambdas via {@link #builder()}.
 *
 * <p>Throw semantics are asymmetric by design: a throwing observer aborts the call it narrates on
 * the model path, attributed to the caller's own {@code tell} — the observer is the caller's code,
 * so its exception is the caller's exception. Tool-progress narration ({@link
 * TurnEvent.ToolCallProgressed}) is different — logged and dropped rather than propagated, because
 * letting it propagate would misattribute a bug in the UI's narration to the tool itself, killing a
 * call that was otherwise succeeding.
 *
 * <p>Threading: progress narration arrives on whatever thread the tool that emits it is running on,
 * which need not be the thread that drove the turn. An observer that only appends deltas to a
 * buffer it owns exclusively is fine either way; an observer that accumulates across events must
 * make itself thread-safe, or restrict itself to delta-only bookkeeping that tolerates out-of-order
 * or concurrent arrival.
 */
public interface TurnObserver {

  void on(TurnEvent event);

  /** The absent audience: accepts everything, tells no one. */
  static TurnObserver noop() {
    return event -> {};
  }

  /**
   * A builder composing an observer from per-variant consumers; see {@link TurnObserverBuilder}.
   */
  static TurnObserverBuilder builder() {
    return new TurnObserverBuilder();
  }

  /**
   * The standard narrating observer — one says-line per {@link TurnEvent.AssistantSaid} (its
   * message's text blocks joined, skipped when blank), a line each for a tool requested, completed,
   * or parked (the parked line carries the token), and the segment's {@link TurnEvent.TurnEnded}
   * line at {@code INFO}, with the failure reason repeated at {@code WARN} when the status is
   * {@code FAILED}. Built on {@link #builder()} — this factory's own dogfood, and the collapse
   * target for what every example used to hand-roll (see {@code night-watchman}'s {@code Watchman},
   * {@code order-desk}'s {@code OrderDesk}, {@code dispatcher}'s {@code IncidentLog}).
   *
   * @param logger the slf4j {@code Logger} every line is written to
   * @param prefix the log-line tag — an incident id, an order id, a conversation label
   */
  static TurnObserver logging(Logger logger, String prefix) {
    Objects.requireNonNull(prefix, "prefix must not be null");
    return logging(logger, () -> prefix);
  }

  /**
   * {@link #logging(Logger, String)}, with the prefix resolved once per event, at the moment that
   * event narrates, rather than fixed up front. Prefer this overload whenever the tag is not yet
   * known when the observer is built — a correlation id minted only once a drive returns, say — and
   * the fixed-{@code String} overload would force capturing a value before it exists.
   *
   * @param logger the slf4j {@code Logger} every line is written to
   * @param prefix supplies the log-line tag on demand, called once per narrated line
   */
  static TurnObserver logging(Logger logger, Supplier<String> prefix) {
    Objects.requireNonNull(logger, "logger must not be null");
    Objects.requireNonNull(prefix, "prefix must not be null");
    return builder()
        .onAssistantSaid(
            said -> {
              String text = joinedText(said.message());
              if (!text.isBlank()) {
                logger.info("{} says: {}", prefix.get(), text);
              }
            })
        .onToolCallRequested(
            requested -> logger.info("{} tool: {}", prefix.get(), requested.call().name()))
        .onToolCallCompleted(
            completed ->
                logger.info(
                    "{} tool completed: {} (error={})",
                    prefix.get(),
                    completed.call().name(),
                    completed.result().isError()))
        .onToolCallParked(
            parked ->
                logger.info(
                    "{} parked: tool={} token={}",
                    prefix.get(),
                    parked.call().name(),
                    parked.token().value()))
        .onTurnEnded(
            ended -> {
              logger.info("{} ends: {}", prefix.get(), ended.status());
              if (ended.status() == ConversationStatus.FAILED) {
                logger.warn("{} failed: {}", prefix.get(), ended.failureReason());
              }
            })
        .build();
  }

  /** The message's {@link TextBlock} content, concatenated in order — no separator, no filler. */
  private static String joinedText(Message message) {
    StringBuilder joined = new StringBuilder();
    for (ContentBlock block : message.content()) {
      if (block instanceof TextBlock(String text)) {
        joined.append(text);
      }
    }
    return joined.toString();
  }
}
