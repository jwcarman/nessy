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
package org.jwcarman.nessy.examples.watchman.pekko;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.nessy.spi.substrate.JournalStore;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * The watchman's transcript, on Nessy's own journal.
 *
 * <p><b>Why this class exists at all is a measurement.</b> The port originally carried the
 * transcript inside {@link TurnState}, and a {@code DurableStateBehavior} rewrites its whole
 * document on every revision. The soak measured the consequence: 1,709 bytes at revision 5, 24,151
 * bytes at revision 64, an hour apart — and all 64 revisions rewrote the entire thing. At that rate
 * a DynamoDB item is over its 400 KB limit inside a day and Postgres is rewriting megabytes just to
 * record that a tool finished.
 *
 * <p>So the transcript moved out, into {@link JournalStore} — <b>append-only, one row per turn,
 * never rewritten</b> — and {@link TurnState} kept only the phase and what is in flight. There is
 * no claim-check id and none is needed: a transcript is addressable by agent id, so recall is a
 * read.
 *
 * <p><b>The ordering rule.</b> A turn is appended BEFORE the state that depends on it is persisted.
 * The two writes cannot be atomic, so one of them has to be able to lose: an orphaned transcript
 * entry is harmless (see {@link #recall}), whereas state referencing a turn that was never written
 * is broken. In this port the rule falls out of who does the work — the party that PRODUCES a turn
 * appends it, on its own blocking thread, and only then tells the agent. Nothing ever appends from
 * a Pekko dispatcher.
 */
public final class Transcript {

  /** The journal kind. Renaming it orphans every transcript, so it is a compatibility surface. */
  public static final String KIND = "watchman-transcript";

  private final Substrate substrate;
  private final JournalStore<Turn> journal;
  private final Codec<Turn> codec;

  public Transcript(Substrate substrate) {
    this.substrate = substrate;
    this.journal = substrate.journal(KIND, Turn.class);
    this.codec = substrate.codecs().create(Turn.class);
  }

  /** Append one turn. Blocking; callers guarantee they are not on a dispatcher. */
  public void append(String agentId, Turn turn) {
    journal.append(agentId, turn);
  }

  /** One turn with the journal's own metadata — what the page shows. */
  public record Entry(long seq, Instant appendedAt, Turn turn) {}

  /**
   * Every turn, with sequence and timestamp.
   *
   * <p>Goes to {@link Substrate#entries} rather than {@link JournalStore#entries} deliberately: the
   * typed façade returns {@code List<T>} and drops both {@code seq} and {@code appendedAt}. Those
   * are exactly what an audit view needs, so this decodes the raw entries itself.
   *
   * <p>That {@code appendedAt} closes an open item: an approval's audit trail lives in the
   * transcript, and until now nothing in it carried a time. The journal stamps every row, so "when
   * was this denied, and by whom" is answerable from the transcript alone.
   */
  public List<Entry> entries(String agentId) {
    return substrate.entries(KIND, agentId, 1).stream()
        .map(entry -> new Entry(entry.seq(), entry.appendedAt(), codec.decode(entry.payload())))
        .toList();
  }

  /**
   * The model's context: DERIVED, recomputed at every call, never persisted and never cached in
   * state. Caching it would be the same mistake the state-bloat measurement was about, one level
   * down.
   *
   * <p><b>The state is the authority on what is in flight; the journal is the narrative.</b>
   * Because the two writes are not atomic, a crash can leave the journal ahead of the state — an
   * assistant turn asking for calls the state never recorded, or a tool result for a call that was
   * re-run and answered twice. Both are dropped here rather than being sent to a model that would
   * reject them (an OpenAI-compatible endpoint requires exactly one result per {@code
   * tool_call_id}, and no unanswered {@code tool_calls} in the middle of a conversation):
   *
   * <ul>
   *   <li>a tool result is kept only the FIRST time its call id appears;
   *   <li>an assistant turn is kept only if every call it asked for is either answered in the
   *       journal or in flight according to {@code state} — anything else is work a crash
   *       abandoned;
   *   <li>a tool result whose assistant turn was dropped goes with it.
   * </ul>
   */
  public List<Turn> recall(String agentId, TurnState state) {
    List<Turn> all = journal.entries(agentId, 1);

    Set<String> inFlight = new HashSet<>();
    if (state instanceof TurnState.WorkingTools working) {
      working.calls().forEach(call -> inFlight.add(call.id()));
    }

    Map<String, Turn.ToolResult> firstResults = new LinkedHashMap<>();
    for (Turn turn : all) {
      if (turn instanceof Turn.ToolResult result) {
        firstResults.putIfAbsent(result.callId(), result);
      }
    }

    Set<String> answered = firstResults.keySet();
    Set<String> keptCallIds = new HashSet<>();
    List<Turn> context = new ArrayList<>();
    for (Turn turn : all) {
      switch (turn) {
        case Turn.User user -> context.add(user);
        case Turn.Assistant assistant -> {
          boolean everyCallAccountedFor =
              assistant.calls().stream()
                  .allMatch(call -> answered.contains(call.id()) || inFlight.contains(call.id()));
          if (everyCallAccountedFor) {
            context.add(assistant);
            assistant.calls().forEach(call -> keptCallIds.add(call.id()));
          }
        }
        case Turn.ToolResult result -> {
          if (firstResults.get(result.callId()) == result
              && keptCallIds.contains(result.callId())) {
            context.add(result);
          }
        }
      }
    }
    return List.copyOf(context);
  }
}
