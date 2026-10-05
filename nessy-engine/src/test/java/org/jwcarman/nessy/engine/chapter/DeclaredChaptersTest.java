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
package org.jwcarman.nessy.engine.chapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.AmbientSource;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.ChapterPolicy;
import org.jwcarman.nessy.api.HarnessConfig;
import org.jwcarman.nessy.api.MemorySource;
import org.jwcarman.nessy.api.OpenTurns;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.StateSource;
import org.jwcarman.nessy.api.Summarizer;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.TurnPolicy;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.turn.Exchange;
import org.jwcarman.nessy.api.turn.Input;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.engine.chapter.DeclaredChapters.Beginning;
import org.jwcarman.nessy.engine.store.TurnHistories;
import org.jwcarman.nessy.engine.store.TurnHistory;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DeclaredChaptersTest {

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
  private static final AgentId AGENT = AgentId.random();
  private static final String BEGINS = "begin_chapter";

  private static TurnId id(long value) {
    return new TurnId(value);
  }

  /** A completed turn numbered {@code number}, whose one exchange calls each of these tools. */
  private static Turn turn(long number, String... tools) {
    List<Block.ActionRequestContent> calls = new ArrayList<>();
    for (String tool : tools) {
      calls.add(new Block.ToolCall("call-" + number + "-" + calls.size(), tool, "{}"));
    }
    List<Exchange> exchanges =
        calls.isEmpty() ? List.of() : List.of(exchangeOf(Seq.of(2), calls, List.of()));
    return new Turn(
        id(number),
        new Input(Seq.of(1), List.of(new Block.Text("turn " + number))),
        exchanges,
        null);
  }

  private static List<TurnId> ids(List<Turn> turns) {
    return turns.stream().map(Turn::id).toList();
  }

  private static DeclaredChapters over(List<Turn> turns) {
    TurnHistories histories =
        (type, agent) ->
            new TurnHistory() {
              @Override
              public List<Turn> turnsBetween(TurnId from, TurnId through) {
                return turns.stream()
                    .filter(
                        t -> t.id().value() >= from.value() && t.id().value() <= through.value())
                    .toList();
              }

              @Override
              public List<Turn> lastTurns(int count) {
                throw new UnsupportedOperationException();
              }

              @Override
              public List<Turn> turnsFrom(long fromTurn) {
                throw new UnsupportedOperationException();
              }

              @Override
              public List<Turn> lastTurnsAfter(TurnId through, int count) {
                throw new UnsupportedOperationException();
              }

              @Override
              public long turnsAfter(long through) {
                throw new UnsupportedOperationException();
              }

              @Override
              public List<TurnId> completedAfter(Optional<TurnId> through) {
                throw new UnsupportedOperationException();
              }
            };
    return new DeclaredChapters(histories);
  }

  private static List<TurnId> endsOver(List<Turn> turns) {
    return over(turns).ends(new OpenTurns(TYPE, AGENT, ids(turns)));
  }

  @Nested
  @DisplayName("Where chapters end")
  class WhereChaptersEnd {

    @Test
    void a_call_in_the_third_of_five_open_turns_ends_a_chapter_at_the_second() {
      List<Turn> turns = List.of(turn(1), turn(2), turn(3, BEGINS), turn(4), turn(5));

      assertThat(endsOver(turns)).containsExactly(id(2));
    }

    @Test
    void a_call_in_the_first_open_turn_ends_nothing() {
      List<Turn> turns = List.of(turn(1, BEGINS), turn(2), turn(3));

      assertThat(endsOver(turns)).isEmpty();
    }

    @Test
    void calls_in_the_third_and_fifth_end_chapters_at_the_second_and_fourth() {
      List<Turn> turns = List.of(turn(1), turn(2), turn(3, BEGINS), turn(4), turn(5, BEGINS));

      assertThat(endsOver(turns)).containsExactly(id(2), id(4));
    }

    @Test
    void no_calls_ends_nothing() {
      List<Turn> turns = List.of(turn(1), turn(2), turn(3));

      assertThat(endsOver(turns)).isEmpty();
    }

    @Test
    void a_call_to_a_different_tool_is_ignored() {
      List<Turn> turns = List.of(turn(1), turn(2), turn(3, "update_plan"), turn(4));

      assertThat(endsOver(turns)).isEmpty();
    }

    @Test
    void two_calls_in_one_turn_end_one_chapter() {
      List<Turn> turns = List.of(turn(1), turn(2), turn(3, BEGINS, BEGINS));

      assertThat(endsOver(turns)).containsExactly(id(2));
    }

    @Test
    void no_open_turns_ends_nothing() {
      DeclaredChapters policy = over(List.of());
      OpenTurns none = new OpenTurns(TYPE, AGENT, List.of());

      assertThat(policy.ends(none)).isEmpty();
    }
  }

  @Nested
  @DisplayName("The tool")
  class TheTool {

    private ToolCallRequest<Beginning> asked(Beginning input) {
      return new ToolCallRequest<>() {
        @Override
        public AgentType agentType() {
          return TYPE;
        }

        @Override
        public AgentId agentId() {
          return AGENT;
        }

        @Override
        public TurnId turn() {
          return id(3);
        }

        @Override
        public CallId callId() {
          return new CallId("c1");
        }

        @Override
        public IdempotencyKey idempotencyKey() {
          return IdempotencyKey.of(UUID.fromString("01999999-0000-7000-8000-000000000001"));
        }

        @Override
        public ToolName toolName() {
          return DeclaredChapters.TOOL_NAME;
        }

        @Override
        public Beginning input() {
          return input;
        }

        @Override
        public Instant deadline() {
          return Instant.now().plusSeconds(30);
        }
      };
    }

    @Test
    void it_is_named_begin_chapter() {
      assertThat(DeclaredChapters.tool().name()).isEqualTo(new ToolName(BEGINS));
    }

    @Test
    void it_acknowledges_with_the_stated_text() {
      Tool<Beginning> tool = DeclaredChapters.tool();

      Awaited<ToolResult> result = tool.call(asked(new Beginning("Taxes")));

      assertThat(result)
          .isEqualTo(
              Awaited.ready(ToolResult.ok(new Block.Text("A new chapter begins with this turn."))));
    }

    @Test
    void its_description_says_when_to_call_it() {
      assertThat(DeclaredChapters.tool().description())
          .contains("new subject")
          .contains("start of the turn");
    }
  }

  @Nested
  @DisplayName("The feature")
  class TheFeature {

    private static final class Recording implements HarnessConfig<Recording> {
      private final List<Tool<?>> tools = new ArrayList<>();
      private final List<ChapterPolicy> policies = new ArrayList<>();

      @Override
      public AgentType agentType() {
        return TYPE;
      }

      @Override
      public Recording turnPolicy(TurnPolicy policy) {
        return this;
      }

      @Override
      public <T> Recording tool(Tool<T> tool) {
        tools.add(tool);
        return this;
      }

      @Override
      public Recording instructions(String text) {
        return this;
      }

      @Override
      public Recording memory(MemorySource source) {
        return this;
      }

      @Override
      public Recording state(StateSource source) {
        return this;
      }

      @Override
      public Recording ambient(AmbientSource source) {
        return this;
      }

      @Override
      public Recording chapterPolicy(ChapterPolicy policy) {
        policies.add(policy);
        return this;
      }

      @Override
      public Recording summarizer(Summarizer summarizer) {
        return this;
      }
    }

    @Test
    void it_installs_one_tool_and_a_declared_chapters_policy() {
      Recording config = new Recording();
      TurnHistories unread = (type, agent) -> null;

      DeclaredChapters.feature(unread).customize(config);

      assertThat(config.tools).hasSize(1);
      assertThat(config.tools.getFirst().name()).isEqualTo(new ToolName(BEGINS));
      assertThat(config.policies).hasSize(1);
      assertThat(config.policies.getFirst()).isInstanceOf(DeclaredChapters.class);
    }
  }
}
