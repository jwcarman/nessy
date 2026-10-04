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
package org.jwcarman.nessy.engine.story;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.CallResult;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.RequestContent;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.StoryContent;
import org.jwcarman.nessy.api.TurnContent;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.backend.event.ActionRequest;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.event.AgentEvents;
import org.jwcarman.nessy.backend.inmemory.InMemoryAgentEvents;
import org.jwcarman.nessy.backend.inmemory.InMemoryPayloads;
import org.jwcarman.nessy.backend.payload.Payloads;
import tools.jackson.databind.json.JsonMapper;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class StoryContentTest {

  private static final AgentType TYPE = new AgentType("desk");
  private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");

  private final JacksonCodecFactory codecs = new JacksonCodecFactory(JsonMapper.builder().build());
  private final AgentEvents events = new InMemoryAgentEvents(codecs);
  private final Payloads payloads = new InMemoryPayloads(codecs);
  private final EventAgentStories stories = new EventAgentStories(events, payloads);
  private final AgentId agent = AgentId.random();
  private final StoryContent content = stories.of(TYPE, agent).content();

  private final IdempotencyKey keyOfSucceeded = IdempotencyKey.of(UUID.randomUUID());
  private final IdempotencyKey keyOfFailed = IdempotencyKey.of(UUID.randomUUID());

  private long last;

  private PayloadRef keep(Block... blocks) {
    return payloads.forAgent(agent).put(List.of(blocks));
  }

  private static ActionRequest call(String id, IdempotencyKey key) {
    return new ActionRequest.ToolCall(CallId.of(id), new ToolName("lookup"), "looks it up", key);
  }

  private void write(AgentEvent... written) {
    events.append(TYPE, agent, List.of(written), new Seq(last), AT);
    last += written.length;
  }

  private AgentEvent.TurnStarted started(PayloadRef input) {
    return new AgentEvent.TurnStarted(new Seq(last + 1), new TurnId(last + 1), input, AT);
  }

  private AgentEvent.ActionsRequested requested(
      long seq, long turn, PayloadRef request, ActionRequest... actions) {
    return new AgentEvent.ActionsRequested(
        new Seq(seq), new TurnId(turn), request, List.of(actions), Usage.unreported());
  }

  private static AgentEvent.ToolSucceeded succeeded(
      long seq, long turn, String callId, PayloadRef result) {
    return new AgentEvent.ToolSucceeded(
        new Seq(seq), new TurnId(turn), CallId.of(callId), result, "done");
  }

  private static AgentEvent.ToolFailed failed(long seq, long turn, String callId) {
    return new AgentEvent.ToolFailed(new Seq(seq), new TurnId(turn), CallId.of(callId), "broke");
  }

  private static AgentEvent.InferenceAnswered answered(long seq, long turn, PayloadRef answer) {
    return new AgentEvent.InferenceAnswered(
        new Seq(seq), new TurnId(turn), answer, Usage.unreported());
  }

  private static Block.ToolCall toolCall(String id) {
    return new Block.ToolCall(CallId.of(id), new ToolName("lookup"), "{}");
  }

  /** One turn: two calls, one succeeds and one fails, then an answer. Seqs 1 to 5. */
  private void scriptedTurn() {
    PayloadRef input = keep(new Block.Text("What is the balance?"));
    PayloadRef request = keep(new Block.Commentary("Let me look."), toolCall("a"), toolCall("b"));
    PayloadRef result = keep(new Block.Text("42"));
    PayloadRef answer = keep(new Block.Text("It is 42."));
    write(
        started(input),
        requested(2, 1, request, call("a", keyOfSucceeded), call("b", keyOfFailed)),
        succeeded(3, 1, "a", result),
        failed(4, 1, "b"),
        answered(5, 1, answer));
  }

  @Nested
  class A_turns_content {

    @Test
    void is_its_input_what_the_model_wrote_and_its_answer() {
      scriptedTurn();

      TurnContent turn = content.turn(new TurnId(1));

      assertThat(turn.input()).containsExactly(new Block.Text("What is the balance?"));
      assertThat(turn.requests())
          .containsExactly(
              new RequestContent(
                  new Seq(2),
                  List.of(new Block.Commentary("Let me look."), toolCall("a"), toolCall("b"))));
      assertThat(turn.answer()).contains(List.of(new Block.Text("It is 42.")));
    }

    @Test
    void still_in_progress_has_no_answer() {
      write(started(keep(new Block.Text("hello"))));

      TurnContent turn = content.turn(new TurnId(1));

      assertThat(turn.input()).containsExactly(new Block.Text("hello"));
      assertThat(turn.requests()).isEmpty();
      assertThat(turn.answer()).isEmpty();
    }

    @Test
    void does_not_include_the_next_turns_content() {
      scriptedTurn();
      write(started(keep(new Block.Text("next"))));

      TurnContent turn = content.turn(new TurnId(1));

      assertThat(turn.input()).containsExactly(new Block.Text("What is the balance?"));
      assertThat(turn.answer()).isPresent();
    }

    @Test
    void of_a_later_turn_is_read_from_that_turn() {
      scriptedTurn();
      write(started(keep(new Block.Text("next"))));

      TurnContent turn = content.turn(new TurnId(6));

      assertThat(turn.input()).containsExactly(new Block.Text("next"));
      assertThat(turn.answer()).isEmpty();
    }

    @Test
    void that_does_not_exist_is_refused() {
      scriptedTurn();

      TurnId missing = new TurnId(99);
      assertThatThrownBy(() -> content.turn(missing))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("no turn 99 in this agent's story");
    }

    @Test
    void at_a_position_that_is_not_the_start_of_a_turn_is_refused() {
      scriptedTurn();

      TurnId missing = new TurnId(3);
      assertThatThrownBy(() -> content.turn(missing))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("no turn 3 in this agent's story");
    }

    @Test
    void of_an_agent_with_no_story_is_refused() {
      TurnId missing = new TurnId(1);
      assertThatThrownBy(() -> content.turn(missing))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("no turn 1 in this agent's story");
    }

    @Test
    void whose_content_has_gone_is_a_fault_that_names_the_reference() {
      PayloadRef gone = new PayloadRef("gone");
      write(started(gone));

      TurnId missing = new TurnId(1);
      assertThatThrownBy(() -> content.turn(missing))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("gone");
    }
  }

  @Nested
  class A_calls_result {

    @Test
    void is_read_by_its_key() {
      scriptedTurn();

      Optional<List<Block.ToolResultContent>> result = content.result(keyOfSucceeded);

      assertThat(result).contains(List.of(new Block.Text("42")));
    }

    @Test
    void is_absent_for_a_failed_call() {
      scriptedTurn();

      assertThat(content.result(keyOfFailed)).isEmpty();
    }

    @Test
    void is_absent_for_a_call_still_running() {
      write(
          started(keep(new Block.Text("go"))),
          requested(2, 1, keep(toolCall("a")), call("a", keyOfSucceeded)));

      assertThat(content.result(keyOfSucceeded)).isEmpty();
    }

    @Test
    void is_absent_for_a_key_that_is_not_in_this_agents_story() {
      scriptedTurn();

      assertThat(content.result(IdempotencyKey.of(UUID.randomUUID()))).isEmpty();
    }

    @Test
    void is_absent_for_an_agent_with_no_story() {
      assertThat(content.result(keyOfSucceeded)).isEmpty();
    }

    @Test
    void is_found_in_a_later_turn() {
      scriptedTurn();
      IdempotencyKey later = IdempotencyKey.of(UUID.randomUUID());
      write(
          started(keep(new Block.Text("again"))),
          requested(7, 6, keep(toolCall("a")), call("a", later)),
          succeeded(8, 6, "a", keep(new Block.Text("43"))));

      assertThat(content.result(later)).contains(List.of(new Block.Text("43")));
    }

    @Test
    void whose_content_has_gone_is_a_fault_that_names_the_reference() {
      write(
          started(keep(new Block.Text("go"))),
          requested(2, 1, keep(toolCall("a")), call("a", keyOfSucceeded)),
          succeeded(3, 1, "a", new PayloadRef("vanished")));

      assertThatThrownBy(() -> content.result(keyOfSucceeded))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("vanished");
    }

    @Test
    void is_not_mistaken_for_another_calls_when_a_call_id_repeats_across_requests() {
      IdempotencyKey first = IdempotencyKey.of(UUID.randomUUID());
      IdempotencyKey second = IdempotencyKey.of(UUID.randomUUID());
      write(
          started(keep(new Block.Text("go"))),
          requested(2, 1, keep(toolCall("c1")), call("c1", first)),
          succeeded(3, 1, "c1", keep(new Block.Text("one"))),
          requested(4, 1, keep(toolCall("c1")), call("c1", second)),
          succeeded(5, 1, "c1", keep(new Block.Text("two"))));

      assertThat(content.result(first)).contains(List.of(new Block.Text("one")));
      assertThat(content.result(second)).contains(List.of(new Block.Text("two")));
    }
  }

  @Nested
  class An_agents_results {

    @Test
    void are_only_the_successful_calls_oldest_first() {
      scriptedTurn();
      IdempotencyKey later = IdempotencyKey.of(UUID.randomUUID());
      write(
          started(keep(new Block.Text("again"))),
          requested(7, 6, keep(toolCall("a")), call("a", later)),
          succeeded(8, 6, "a", keep(new Block.Text("43"))));

      List<CallResult> results = content.results(Seq.NONE, 10);

      assertThat(results)
          .containsExactly(
              new CallResult(new Seq(3), keyOfSucceeded, List.of(new Block.Text("42"))),
              new CallResult(new Seq(8), later, List.of(new Block.Text("43"))));
    }

    @Test
    void after_a_position_skip_what_came_before() {
      scriptedTurn();
      IdempotencyKey later = IdempotencyKey.of(UUID.randomUUID());
      write(
          started(keep(new Block.Text("again"))),
          requested(7, 6, keep(toolCall("a")), call("a", later)),
          succeeded(8, 6, "a", keep(new Block.Text("43"))));

      List<CallResult> results = content.results(new Seq(3), 10);

      assertThat(results).extracting(CallResult::seq).containsExactly(new Seq(8));
      assertThat(results).extracting(CallResult::idempotencyKey).containsExactly(later);
    }

    @Test
    void after_the_request_that_made_the_key_still_have_that_key() {
      scriptedTurn();

      List<CallResult> results = content.results(new Seq(2), 10);

      assertThat(results)
          .containsExactly(
              new CallResult(new Seq(3), keyOfSucceeded, List.of(new Block.Text("42"))));
    }

    @Test
    void after_a_position_in_the_middle_of_a_long_turn_have_their_own_keys() {
      write(started(keep(new Block.Text("go"))));
      PayloadRef request = keep(toolCall("c1"));
      List<IdempotencyKey> keys = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        IdempotencyKey key = IdempotencyKey.of(UUID.randomUUID());
        keys.add(key);
        write(
            requested(last + 1, 1, request, call("c1", key)),
            succeeded(last + 2, 1, "c1", keep(new Block.Text("r" + i))));
      }

      List<CallResult> results = content.results(new Seq(5), 10);

      assertThat(results)
          .extracting(CallResult::idempotencyKey)
          .containsExactly(keys.get(2), keys.get(3));
      assertThat(results)
          .extracting(CallResult::blocks)
          .containsExactly(List.of(new Block.Text("r2")), List.of(new Block.Text("r3")));
    }

    @Test
    void after_a_position_that_is_past_the_end_are_empty() {
      scriptedTurn();

      assertThat(content.results(new Seq(50), 10)).isEmpty();
    }

    @Test
    void return_no_more_than_their_limit() {
      scriptedTurn();
      write(
          started(keep(new Block.Text("again"))),
          requested(7, 6, keep(toolCall("a")), call("a", IdempotencyKey.of(UUID.randomUUID()))),
          succeeded(8, 6, "a", keep(new Block.Text("43"))));

      List<CallResult> results = content.results(Seq.NONE, 1);

      assertThat(results).extracting(CallResult::seq).containsExactly(new Seq(3));
    }

    @Test
    void are_read_across_pages_of_the_story() {
      write(started(keep(new Block.Text("go"))));
      PayloadRef request = keep(toolCall("c1"));
      PayloadRef result = keep(new Block.Text("r"));
      long calls = 1_200;
      for (int i = 0; i < calls; i++) {
        write(
            requested(last + 1, 1, request, call("c1", IdempotencyKey.of(UUID.randomUUID()))),
            succeeded(last + 2, 1, "c1", result));
      }

      List<CallResult> results = content.results(new Seq(2_000), Integer.MAX_VALUE);

      assertThat(results).hasSize(201);
      assertThat(results.getLast().seq()).isEqualTo(new Seq(2 * calls + 1));
    }

    @Test
    void after_the_position_that_terminated_the_agent_are_empty() {
      scriptedTurn();
      write(new AgentEvent.Terminated(new Seq(6)));

      assertThat(content.results(new Seq(6), 10)).isEmpty();
    }

    @Test
    void of_an_agent_with_no_story_are_empty() {
      assertThat(content.results(Seq.NONE, 10)).isEmpty();
    }

    @Test
    void whose_content_has_gone_are_a_fault_that_names_the_reference() {
      write(
          started(keep(new Block.Text("go"))),
          requested(2, 1, keep(toolCall("a")), call("a", keyOfSucceeded)),
          succeeded(3, 1, "a", new PayloadRef("vanished")));

      assertThatThrownBy(() -> content.results(Seq.NONE, 10))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("vanished");
    }

    @Test
    void refuse_a_limit_that_is_not_positive() {
      assertThatThrownBy(() -> content.results(Seq.NONE, 0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("limit must be positive");
    }

    @Test
    void refuse_a_missing_position() {
      assertThatThrownBy(() -> content.results(null, 10))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("after must not be null");
    }
  }
}
