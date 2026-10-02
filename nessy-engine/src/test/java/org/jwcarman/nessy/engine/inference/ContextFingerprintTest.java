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
package org.jwcarman.nessy.engine.inference;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Ambient;
import org.jwcarman.nessy.api.Memory;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.State;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.turn.Chapter;
import org.jwcarman.nessy.api.turn.Exchange;
import org.jwcarman.nessy.api.turn.Input;
import org.jwcarman.nessy.api.turn.Summary;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.inference.InferenceContext;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ContextFingerprintTest {

  private static Exchange exchangeOf(
      Seq seq, List<Block.ActionRequestContent> request, List<ToolOutcome> outcomes) {
    Map<CallId, String> actions = new LinkedHashMap<>();
    for (Block.ActionRequestContent block : request) {
      if (block instanceof Block.ToolCall call) {
        actions.put(call.id(), "did " + call.name().value());
      }
    }
    Map<CallId, String> results = new LinkedHashMap<>();
    for (ToolOutcome outcome : outcomes) {
      if (outcome instanceof ToolOutcome.Succeeded done && actions.containsKey(done.callId())) {
        results.put(done.callId(), "returned for " + done.callId().value());
      }
    }
    return new Exchange(seq, request, outcomes, actions, results);
  }

  private static final AgentType TYPE = new AgentType("chat");
  private static final AgentId AGENT = new AgentId(UUID.randomUUID());

  private static Turn turn(long id, String text, List<Exchange> exchanges) {
    return new Turn(
        new TurnId(id),
        new Input(new Seq(id), List.<Block.InputContent>of(new Block.Text(text))),
        exchanges,
        null,
        0);
  }

  private static Turn turn(long id, String text) {
    return turn(id, text, List.of());
  }

  private static InferenceContext context(
      List<Summary> summaries,
      List<Turn> tail,
      List<Memory> memory,
      List<State> state,
      Turn active,
      List<Ambient> ambient) {
    return new InferenceContext(summaries, tail, memory, state, active, ambient);
  }

  private static InferenceContext base() {
    return context(
        List.of(),
        List.of(turn(1, "hello")),
        List.of(Memory.text("episodes", "met on Tuesday")),
        List.of(State.text("plan", "step one")),
        turn(2, "and now?"),
        List.of(Ambient.text("clock", "it is noon")));
  }

  private static String changeFrom(InferenceContext earlier, InferenceContext later) {
    return ContextFingerprint.of(later).firstChangeSince(ContextFingerprint.of(earlier));
  }

  @Nested
  class When_nothing_changed {

    @Test
    void identical_contexts_report_none() {
      assertThat(changeFrom(base(), base())).isEqualTo("none");
    }
  }

  @Nested
  class When_history_changed {

    @Test
    void a_new_turn_in_the_tail_reports_history() {
      InferenceContext later =
          context(
              List.of(),
              List.of(turn(1, "hello"), turn(2, "and now?")),
              List.of(Memory.text("episodes", "met on Tuesday")),
              List.of(State.text("plan", "step one")),
              turn(3, "next"),
              List.of(Ambient.text("clock", "it is noon")));

      assertThat(changeFrom(base(), later)).isEqualTo("history");
    }

    @Test
    void a_new_summary_reports_history() {
      Summary summary =
          new Summary(new Chapter(TYPE, AGENT, new TurnId(1), new TurnId(1)), "they said hello");
      InferenceContext later =
          context(
              List.of(summary),
              List.of(turn(1, "hello")),
              List.of(Memory.text("episodes", "met on Tuesday")),
              List.of(State.text("plan", "step one")),
              turn(2, "and now?"),
              List.of(Ambient.text("clock", "it is noon")));

      assertThat(changeFrom(base(), later)).isEqualTo("history");
    }
  }

  @Nested
  class When_a_later_stratum_changed {

    @Test
    void changed_memory_with_unchanged_history_reports_memory() {
      InferenceContext later =
          context(
              List.of(),
              List.of(turn(1, "hello")),
              List.of(Memory.text("episodes", "met on Wednesday")),
              List.of(State.text("plan", "step one")),
              turn(2, "and now?"),
              List.of(Ambient.text("clock", "it is noon")));

      assertThat(changeFrom(base(), later)).isEqualTo("memory");
    }

    @Test
    void changed_state_reports_state() {
      InferenceContext later =
          context(
              List.of(),
              List.of(turn(1, "hello")),
              List.of(Memory.text("episodes", "met on Tuesday")),
              List.of(State.text("plan", "step two")),
              turn(2, "and now?"),
              List.of(Ambient.text("clock", "it is noon")));

      assertThat(changeFrom(base(), later)).isEqualTo("state");
    }

    @Test
    void a_new_exchange_in_the_active_turn_reports_active_turn() {
      InferenceContext later =
          context(
              List.of(),
              List.of(turn(1, "hello")),
              List.of(Memory.text("episodes", "met on Tuesday")),
              List.of(State.text("plan", "step one")),
              turn(2, "and now?", List.of(exchangeOf(new Seq(3), List.of(), List.of()))),
              List.of(Ambient.text("clock", "it is noon")));

      assertThat(changeFrom(base(), later)).isEqualTo("active-turn");
    }

    @Test
    void changed_ambient_alone_reports_ambient() {
      InferenceContext later =
          context(
              List.of(),
              List.of(turn(1, "hello")),
              List.of(Memory.text("episodes", "met on Tuesday")),
              List.of(State.text("plan", "step one")),
              turn(2, "and now?"),
              List.of(Ambient.text("clock", "it is one")));

      assertThat(changeFrom(base(), later)).isEqualTo("ambient");
    }

    @Test
    void when_two_strata_changed_the_earlier_one_is_reported() {
      InferenceContext later =
          context(
              List.of(),
              List.of(turn(1, "hello")),
              List.of(Memory.text("episodes", "met on Tuesday")),
              List.of(State.text("plan", "step two")),
              turn(2, "and now?"),
              List.of(Ambient.text("clock", "it is one")));

      assertThat(changeFrom(base(), later)).isEqualTo("state");
    }
  }
}
