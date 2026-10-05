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
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.CallFailure;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

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

  private static ObjectNode none() {
    return JsonNodeFactory.instance.objectNode();
  }

  private static ActionRequest call(String id, IdempotencyKey key) {
    return new ActionRequest.ToolCall(CallId.of(id), new ToolName("lookup"), "looks it up", key);
  }

  private void write(AgentEvent... written) {
    events.append(TYPE, agent, List.of(written), new Seq(last), AT);
    last += written.length;
  }

  private AgentEvent.TurnStarted started(PayloadRef input) {
    return new AgentEvent.TurnStarted(
        new Seq(last + 1), new TurnId(last + 1), input, "Question", AT, AT);
  }

  private AgentEvent.ActionsRequested requested(
      long seq, long turn, PayloadRef request, ActionRequest... actions) {
    return new AgentEvent.ActionsRequested(
        new Seq(seq),
        new TurnId(turn),
        request,
        List.of(actions),
        Usage.unreported(),
        Optional.empty());
  }

  private static AgentEvent.ToolSucceeded succeeded(
      long seq, long turn, String callId, IdempotencyKey key, PayloadRef result) {
    return new AgentEvent.ToolSucceeded(
        new Seq(seq), new TurnId(turn), CallId.of(callId), result, "done", key);
  }

  private static AgentEvent.ToolFailed failed(
      long seq, long turn, String callId, IdempotencyKey key) {
    return new AgentEvent.ToolFailed(
        new Seq(seq),
        new TurnId(turn),
        CallId.of(callId),
        CallFailure.FAILED,
        "broke",
        JsonNodeFactory.instance.objectNode(),
        key);
  }

  private static AgentEvent.InferenceAnswered answered(long seq, long turn, PayloadRef answer) {
    return new AgentEvent.InferenceAnswered(
        new Seq(seq), new TurnId(turn), answer, false, Usage.unreported(), Optional.empty());
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
        succeeded(3, 1, "a", keyOfSucceeded, result),
        failed(4, 1, "b", keyOfFailed),
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
          succeeded(8, 6, "a", later, keep(new Block.Text("43"))));

      assertThat(content.result(later)).contains(List.of(new Block.Text("43")));
    }

    @Test
    void whose_content_has_gone_is_a_fault_that_names_the_reference() {
      write(
          started(keep(new Block.Text("go"))),
          requested(2, 1, keep(toolCall("a")), call("a", keyOfSucceeded)),
          succeeded(3, 1, "a", keyOfSucceeded, new PayloadRef("vanished")));

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
          succeeded(3, 1, "c1", first, keep(new Block.Text("one"))),
          requested(4, 1, keep(toolCall("c1")), call("c1", second)),
          succeeded(5, 1, "c1", second, keep(new Block.Text("two"))));

      assertThat(content.result(first)).contains(List.of(new Block.Text("one")));
      assertThat(content.result(second)).contains(List.of(new Block.Text("two")));
    }
  }

  @Nested
  class A_call_that_failed_or_was_denied {

    private final CountingEvents counting = new CountingEvents(events);
    private final StoryContent counted =
        new EventAgentStories(counting, payloads).of(TYPE, agent).content();

    private static AgentEvent.ToolDenied denied(
        long seq, long turn, String callId, IdempotencyKey key) {
      return new AgentEvent.ToolDenied(
          new Seq(seq), new TurnId(turn), CallId.of(callId), "no", Optional.empty(), none(), key);
    }

    /** Fills the story after the call's own events with more than two pages of other calls. */
    private void laterCalls(int calls) {
      PayloadRef request = keep(toolCall("c1"));
      PayloadRef result = keep(new Block.Text("r"));
      for (int i = 0; i < calls; i++) {
        IdempotencyKey key = IdempotencyKey.of(UUID.randomUUID());
        write(
            requested(last + 1, 1, request, call("c1", key)),
            succeeded(last + 2, 1, "c1", key, result));
      }
    }

    @Test
    void has_no_result_and_the_read_stops_at_its_own_event() {
      write(
          started(keep(new Block.Text("go"))),
          requested(2, 1, keep(toolCall("a")), call("a", keyOfFailed)),
          failed(3, 1, "a", keyOfFailed));
      laterCalls(1_200);

      assertThat(counted.result(keyOfFailed)).isEmpty();
      assertThat(counting.pagesAfter).isNotEmpty();
      assertThat(counting.pagesAfter).allMatch(read -> read.value() == 0);
    }

    @Test
    void that_was_denied_has_no_result_and_the_read_stops_at_its_own_event() {
      write(
          started(keep(new Block.Text("go"))),
          requested(2, 1, keep(toolCall("a")), call("a", keyOfFailed)),
          denied(3, 1, "a", keyOfFailed));
      laterCalls(1_200);

      assertThat(counted.result(keyOfFailed)).isEmpty();
      assertThat(counting.pagesAfter).isNotEmpty();
      assertThat(counting.pagesAfter).allMatch(read -> read.value() == 0);
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
          succeeded(8, 6, "a", later, keep(new Block.Text("43"))));

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
          succeeded(8, 6, "a", later, keep(new Block.Text("43"))));

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
            succeeded(last + 2, 1, "c1", key, keep(new Block.Text("r" + i))));
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
    void after_a_position_inside_a_turn_that_holds_a_deferral_start_at_that_turn() {
      scriptedTurn();
      IdempotencyKey later = IdempotencyKey.of(UUID.randomUUID());
      write(
          started(keep(new Block.Text("again"))),
          requested(7, 6, keep(toolCall("a")), call("a", later)),
          new AgentEvent.ToolDeferred(new Seq(8), new TurnId(6), CallId.of("a"), AT, later),
          succeeded(9, 6, "a", later, keep(new Block.Text("43"))));

      List<CallResult> results = content.results(new Seq(8), 10);

      assertThat(results).extracting(CallResult::seq).containsExactly(new Seq(9));
      assertThat(results).extracting(CallResult::idempotencyKey).containsExactly(later);
    }

    @Test
    void after_a_position_at_an_approval_deferral_start_at_that_turn() {
      IdempotencyKey key = IdempotencyKey.of(UUID.randomUUID());
      write(
          started(keep(new Block.Text("go"))),
          requested(2, 1, keep(toolCall("a")), call("a", key)),
          new AgentEvent.ApprovalDeferred(
              new Seq(3),
              new TurnId(1),
              CallId.of("a"),
              AT,
              JsonNodeFactory.instance.objectNode(),
              key),
          succeeded(4, 1, "a", key, keep(new Block.Text("42"))));

      List<CallResult> results = content.results(new Seq(3), 10);

      assertThat(results)
          .containsExactly(new CallResult(new Seq(4), key, List.of(new Block.Text("42"))));
    }

    @Test
    void after_a_position_that_is_past_the_end_are_empty() {
      scriptedTurn();

      assertThat(content.results(new Seq(50), 10)).isEmpty();
    }

    @Test
    void return_no_more_than_their_limit() {
      scriptedTurn();
      IdempotencyKey later = IdempotencyKey.of(UUID.randomUUID());
      write(
          started(keep(new Block.Text("again"))),
          requested(7, 6, keep(toolCall("a")), call("a", later)),
          succeeded(8, 6, "a", later, keep(new Block.Text("43"))));

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
        IdempotencyKey key = IdempotencyKey.of(UUID.randomUUID());
        write(
            requested(last + 1, 1, request, call("c1", key)),
            succeeded(last + 2, 1, "c1", key, result));
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
          succeeded(3, 1, "a", keyOfSucceeded, new PayloadRef("vanished")));

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

  @Nested
  class The_facts_a_calls_approver_was_shown {

    private final CountingEvents counting = new CountingEvents(events);
    private final StoryContent counted =
        new EventAgentStories(counting, payloads).of(TYPE, agent).content();

    private final IdempotencyKey first = IdempotencyKey.of(UUID.randomUUID());
    private final IdempotencyKey second = IdempotencyKey.of(UUID.randomUUID());

    private static ObjectNode facts(String action) {
      return JsonNodeFactory.instance.objectNode().put("action", action).put("count", 3);
    }

    /** Compared as text: a number that went through storage reads back in its narrowest type. */
    private String text(Optional<JsonNode> read) {
      assertThat(read).isPresent();
      return read.get().toString();
    }

    private AgentEvent.ToolApproved approved(
        long seq, String callId, IdempotencyKey key, ObjectNode facts) {
      return new AgentEvent.ToolApproved(
          new Seq(seq), new TurnId(1), CallId.of(callId), Optional.of("u_carol"), facts, key);
    }

    private AgentEvent.ToolDenied denied(
        long seq, String callId, IdempotencyKey key, ObjectNode facts) {
      return new AgentEvent.ToolDenied(
          new Seq(seq), new TurnId(1), CallId.of(callId), "no", Optional.of("u_dave"), facts, key);
    }

    private AgentEvent.ApprovalDeferred deferred(
        long seq, String callId, IdempotencyKey key, ObjectNode facts) {
      return new AgentEvent.ApprovalDeferred(
          new Seq(seq), new TurnId(1), CallId.of(callId), AT.plusSeconds(60), facts, key);
    }

    private AgentEvent.ToolFailed failedWith(
        long seq, String callId, IdempotencyKey key, ObjectNode facts) {
      return new AgentEvent.ToolFailed(
          new Seq(seq),
          new TurnId(1),
          CallId.of(callId),
          CallFailure.NOT_AUTHORISED,
          "broke",
          facts,
          key);
    }

    private AgentEvent.TurnStarted opening() {
      return started(keep(new Block.Text("go")));
    }

    private AgentEvent.ActionsRequested asking(ActionRequest... actions) {
      return requested(2, 1, keep(toolCall("a")), actions);
    }

    private void laterCalls(int calls) {
      PayloadRef request = keep(toolCall("c1"));
      PayloadRef result = keep(new Block.Text("r"));
      for (int i = 0; i < calls; i++) {
        IdempotencyKey key = IdempotencyKey.of(UUID.randomUUID());
        write(
            requested(last + 1, 1, request, call("c1", key)),
            succeeded(last + 2, 1, "c1", key, result));
      }
    }

    @Test
    void are_the_approvals_own_when_the_call_was_approved_at_once() {
      write(opening(), asking(call("a", first)), approved(3, "a", first, facts("approve me")));

      assertThat(text(content.approvalFacts(first))).isEqualTo(facts("approve me").toString());
    }

    @Test
    void are_the_denials_own_when_the_call_was_denied_at_once() {
      write(opening(), asking(call("a", first)), denied(3, "a", first, facts("deny me")));

      assertThat(text(content.approvalFacts(first))).isEqualTo(facts("deny me").toString());
    }

    @Test
    void are_an_empty_object_when_the_call_was_decided_at_once_with_none() {
      write(opening(), asking(call("a", first)), approved(3, "a", first, none()));

      assertThat(content.approvalFacts(first)).contains(none());
    }

    @Test
    void are_the_deferrals_while_the_call_is_still_waiting() {
      write(opening(), asking(call("a", first)), deferred(3, "a", first, facts("wait for me")));

      assertThat(text(content.approvalFacts(first))).isEqualTo(facts("wait for me").toString());
    }

    @Test
    void are_an_empty_object_while_the_call_waits_on_a_deferral_that_had_none() {
      write(opening(), asking(call("a", first)), deferred(3, "a", first, none()));

      assertThat(content.approvalFacts(first)).contains(none());
    }

    @Test
    void are_the_deferrals_when_the_call_was_approved_later_with_none_of_its_own() {
      write(
          opening(),
          asking(call("a", first)),
          deferred(3, "a", first, facts("later yes")),
          approved(4, "a", first, none()));

      assertThat(text(content.approvalFacts(first))).isEqualTo(facts("later yes").toString());
    }

    @Test
    void are_the_deferrals_when_the_call_was_denied_later_with_none_of_its_own() {
      write(
          opening(),
          asking(call("a", first)),
          deferred(3, "a", first, facts("later no")),
          denied(4, "a", first, none()));

      assertThat(text(content.approvalFacts(first))).isEqualTo(facts("later no").toString());
    }

    @Test
    void are_the_deferrals_when_the_call_expired_with_a_failure_that_has_none() {
      write(
          opening(),
          asking(call("a", first)),
          deferred(3, "a", first, facts("never answered")),
          failedWith(4, "a", first, none()));

      assertThat(text(content.approvalFacts(first))).isEqualTo(facts("never answered").toString());
    }

    @Test
    void are_the_failures_own_when_the_approver_threw() {
      write(
          opening(),
          asking(call("a", first)),
          failedWith(3, "a", first, facts("asked and failed")));

      assertThat(text(content.approvalFacts(first)))
          .isEqualTo(facts("asked and failed").toString());
    }

    @Test
    void are_the_failures_own_when_the_approver_threw_after_an_earlier_deferral() {
      write(
          opening(),
          asking(call("a", first)),
          deferred(3, "a", first, facts("asked early")),
          failedWith(4, "a", first, facts("asked again and failed")));

      assertThat(text(content.approvalFacts(first)))
          .isEqualTo(facts("asked again and failed").toString());
    }

    @Test
    void stay_the_approvals_when_the_tool_then_failed_with_none() {
      write(
          opening(),
          asking(call("a", first)),
          approved(3, "a", first, facts("approved then ran")),
          failedWith(4, "a", first, none()));

      assertThat(text(content.approvalFacts(first)))
          .isEqualTo(facts("approved then ran").toString());
    }

    @Test
    void stay_the_approvals_when_the_tool_then_succeeded() {
      write(
          opening(),
          asking(call("a", first)),
          approved(3, "a", first, facts("approved then succeeded")),
          succeeded(4, 1, "a", first, keep(new Block.Text("done"))));

      assertThat(text(content.approvalFacts(first)))
          .isEqualTo(facts("approved then succeeded").toString());
    }

    @Test
    void are_the_last_deferrals_when_the_call_was_asked_again() {
      write(
          opening(),
          asking(call("a", first)),
          deferred(3, "a", first, facts("asked early")),
          deferred(4, "a", first, facts("asked again")),
          approved(5, "a", first, none()));

      assertThat(text(content.approvalFacts(first))).isEqualTo(facts("asked again").toString());
    }

    @Test
    void are_each_calls_own_when_two_calls_were_made_in_one_request() {
      write(
          opening(),
          asking(call("a", first), call("b", second)),
          approved(3, "a", first, facts("the first call")),
          deferred(4, "b", second, facts("the second call")));

      assertThat(text(content.approvalFacts(first))).isEqualTo(facts("the first call").toString());
      assertThat(text(content.approvalFacts(second)))
          .isEqualTo(facts("the second call").toString());
    }

    @Test
    void are_the_ones_an_ungated_call_was_shown_because_every_call_goes_to_an_approver() {
      write(
          opening(),
          asking(call("a", first)),
          approved(3, "a", first, facts("allowed by default")),
          succeeded(4, 1, "a", first, keep(new Block.Text("done"))));

      assertThat(text(content.approvalFacts(first)))
          .isEqualTo(facts("allowed by default").toString());
    }

    @Test
    void are_unknown_when_the_key_is_not_in_the_story() {
      write(opening(), asking(call("a", first)), approved(3, "a", first, facts("someone else's")));

      assertThat(content.approvalFacts(second)).isEmpty();
    }

    @Test
    void are_unknown_when_the_call_has_not_yet_been_put_to_its_approver() {
      write(opening(), asking(call("a", first)));

      assertThat(content.approvalFacts(first)).isEmpty();
    }

    @Test
    void are_unknown_for_an_agent_with_no_story() {
      assertThat(content.approvalFacts(first)).isEmpty();
    }

    @Test
    void are_refused_for_a_null_key() {
      assertThatThrownBy(() -> content.approvalFacts(null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void are_read_without_reading_past_the_decision() {
      write(
          opening(), asking(call("a", first)), approved(3, "a", first, facts("decided long ago")));
      laterCalls(1_200);

      assertThat(counted.approvalFacts(first)).isPresent();
      assertThat(counting.pagesAfter).isNotEmpty();
      assertThat(counting.pagesAfter).allMatch(read -> read.value() == 0);
    }

    @Test
    void are_read_without_reading_past_a_failure_of_the_ask() {
      write(
          opening(),
          asking(call("a", first)),
          deferred(3, "a", first, facts("asked")),
          failedWith(4, "a", first, none()));
      laterCalls(1_200);

      assertThat(counted.approvalFacts(first)).isPresent();
      assertThat(counting.pagesAfter).isNotEmpty();
      assertThat(counting.pagesAfter).allMatch(read -> read.value() == 0);
    }

    @Test
    void are_read_to_the_end_of_the_story_while_the_call_is_waiting() {
      write(opening(), asking(call("a", first)), deferred(3, "a", first, facts("still waiting")));
      laterCalls(1_200);

      assertThat(counted.approvalFacts(first)).isPresent();
      assertThat(counting.pagesAfter).isNotEmpty();
      assertThat(counting.pagesAfter).anyMatch(read -> read.value() >= 2_000);
    }
  }

  /** Counts the pages read from the story it wraps. */
  private static final class CountingEvents implements AgentEvents {
    private final AgentEvents delegate;
    private final List<Seq> pagesAfter = new ArrayList<>();

    CountingEvents(AgentEvents delegate) {
      this.delegate = delegate;
    }

    @Override
    public void append(
        AgentType type, AgentId agent, List<AgentEvent> events, Seq expectedLast, Instant at) {
      delegate.append(type, agent, events, expectedLast, at);
    }

    @Override
    public Stream<AgentEvent> streamFrom(AgentType type, AgentId agent, Seq after) {
      return delegate.streamFrom(type, agent, after);
    }

    @Override
    public List<Written> readWrittenFrom(AgentType type, AgentId agent, Seq after, int limit) {
      pagesAfter.add(after);
      return delegate.readWrittenFrom(type, agent, after, limit);
    }

    @Override
    public List<AgentEvent> sinceLastTurnStarted(AgentType type, AgentId agent) {
      return delegate.sinceLastTurnStarted(type, agent);
    }

    @Override
    public Instant writtenAt(AgentType type, AgentId agent, Seq seq) {
      return delegate.writtenAt(type, agent, seq);
    }
  }

  @Nested
  class Every_successful_result {

    private final CountingEvents counting = new CountingEvents(events);
    private final StoryContent counted =
        new EventAgentStories(counting, payloads).of(TYPE, agent).content();

    /** Stores {@code calls} successful calls in one turn: result n is at seq 2n + 2. */
    private void storeResults(int calls) {
      write(started(keep(new Block.Text("go"))));
      PayloadRef request = keep(toolCall("c1"));
      PayloadRef result = keep(new Block.Text("r"));
      for (int i = 0; i < calls; i++) {
        IdempotencyKey key = IdempotencyKey.of(UUID.randomUUID());
        write(
            requested(last + 1, 1, request, call("c1", key)),
            succeeded(last + 2, 1, "c1", key, result));
      }
    }

    @Test
    void is_every_result_once_and_in_order_across_pages() {
      storeResults(2_500);

      List<CallResult> all = counted.allResults(Seq.NONE).toList();

      assertThat(all).hasSize(2_500);
      assertThat(all).extracting(CallResult::seq).isSorted().doesNotHaveDuplicates();
      assertThat(all.getFirst().seq()).isEqualTo(new Seq(3));
      assertThat(all.getLast().seq()).isEqualTo(new Seq(5_001));
    }

    @Test
    void ends_after_exactly_one_full_page_of_results() {
      storeResults(1_000);

      List<CallResult> all = counted.allResults(Seq.NONE).toList();

      assertThat(all).hasSize(1_000);
      assertThat(all).extracting(CallResult::seq).isSorted().doesNotHaveDuplicates();
      assertThat(all.getLast().seq()).isEqualTo(new Seq(2_001));
    }

    @Test
    void after_a_position_skips_what_came_before() {
      storeResults(2_500);

      List<CallResult> all = counted.allResults(new Seq(2_001)).toList();

      assertThat(all).hasSize(1_500);
      assertThat(all.getFirst().seq()).isEqualTo(new Seq(2_003));
    }

    @Test
    void reads_only_the_first_page_of_results_when_the_first_result_is_enough() {
      storeResults(2_500);

      boolean found = counted.allResults(Seq.NONE).anyMatch(result -> result.seq().value() == 3);

      assertThat(found).isTrue();
      // One page of results is 1,000 results, which ends at seq 2,001 and takes three event reads.
      // A second page would rescan from the start of its turn, so its reads would go on past seq
      // 3,000; none did.
      assertThat(counting.pagesAfter).isNotEmpty();
      assertThat(counting.pagesAfter).allMatch(read -> read.value() <= 2_000);
    }

    @Test
    void reads_nothing_until_the_stream_is_consumed() {
      storeResults(2_500);

      counted.allResults(Seq.NONE);

      assertThat(counting.pagesAfter).isEmpty();
    }

    @Test
    void of_an_agent_with_no_story_is_empty() {
      assertThat(counted.allResults(Seq.NONE).toList()).isEmpty();
    }

    @Test
    void refuses_a_missing_position() {
      assertThatThrownBy(() -> counted.allResults(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("after must not be null");
    }
  }
}
