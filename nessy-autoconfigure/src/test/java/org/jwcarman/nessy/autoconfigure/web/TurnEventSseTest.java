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
package org.jwcarman.nessy.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.Role;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.autoconfigure.web.TurnEventSse.Event;

class TurnEventSseTest {

  private static final ToolCall CALL =
      new ToolCall("c1", "issue_coupon", JsonNodeFactory.instance.objectNode());
  private static final ParkToken TOKEN = ParkToken.generate();

  private static List<TurnEvent> oneOfEveryVariant() {
    return List.of(
        new TurnEvent.TextDelta("prose"),
        new TurnEvent.ThinkingDelta("hmm"),
        new TurnEvent.RedactedThinking("opaque"),
        new TurnEvent.ToolCallRequested(CALL),
        new TurnEvent.ToolCallDecided(CALL, Decision.allow()),
        new TurnEvent.ToolCallCompleted(CALL, ToolResult.ok("done")),
        new TurnEvent.ToolCallProgressed(CALL, "halfway"),
        new TurnEvent.ToolCallParked(CALL, TOKEN));
  }

  @Nested
  class Of {

    @Test
    void every_variant_maps_to_its_named_payload() {
      List<TurnEvent> variants = oneOfEveryVariant();
      List<Event> mapped =
          variants.stream().map(TurnEventSse::of).map(Optional::orElseThrow).toList();

      assertThat(mapped.subList(0, mapped.size() - 1))
          .containsExactly(
              new Event("delta", Map.of("text", "prose")),
              new Event("thinking", Map.of("text", "hmm")),
              new Event("thinking", Map.of("text", "[redacted]")),
              new Event("tool-requested", Map.of("id", "c1", "name", "issue_coupon")),
              new Event("tool-decided", Map.of("id", "c1", "allowed", true)),
              new Event("tool-completed", Map.of("id", "c1", "error", false)),
              new Event("tool-progress", Map.of("id", "c1", "message", "halfway")));
      // The last variant, ToolCallParked, has its own dedicated coverage below — its payload
      // is not a fixed literal (args is a pretty-printed rendering of the call's arguments).
      assertThat(mapped.get(mapped.size() - 1).name()).isEqualTo("tool-parked");
    }

    @Test
    void a_denied_decision_maps_with_its_verdict() {
      assertThat(TurnEventSse.of(new TurnEvent.ToolCallDecided(CALL, new Decision.Deny("no"))))
          .contains(new Event("tool-decided", Map.of("id", "c1", "allowed", false)));
    }

    @Test
    void an_errored_completion_maps_with_its_verdict() {
      assertThat(TurnEventSse.of(new TurnEvent.ToolCallCompleted(CALL, ToolResult.error("boom"))))
          .contains(new Event("tool-completed", Map.of("id", "c1", "error", true)));
    }

    @Test
    void a_park_is_named_tool_parked() {
      assertThat(TurnEventSse.of(new TurnEvent.ToolCallParked(CALL, TOKEN)).orElseThrow().name())
          .isEqualTo("tool-parked");
    }

    @Test
    void a_park_s_payload_carries_the_token_tool_and_pretty_printed_args() {
      ObjectNode args = JsonNodeFactory.instance.objectNode();
      args.put("coupon", "SAVE10");
      ToolCall callWithArgs = new ToolCall("c1", "issue_coupon", args);

      Map<String, Object> payload =
          TurnEventSse.of(new TurnEvent.ToolCallParked(callWithArgs, TOKEN))
              .orElseThrow()
              .payload();

      assertThat(payload)
          .containsEntry("token", TOKEN.value())
          .containsEntry("tool", "issue_coupon");
      assertThat(payload.get("args")).isInstanceOf(String.class);
      assertThat((String) payload.get("args")).contains("\n");
    }

    @Nested
    class Assistant_said {

      @Test
      void a_message_with_prose_maps_to_a_message_event_joining_its_text_blocks_in_order() {
        Message message =
            new Message(Role.ASSISTANT, List.of(new TextBlock("coupon "), new TextBlock("issued")));

        Optional<Event> mapped = TurnEventSse.of(new TurnEvent.AssistantSaid(message));

        assertThat(mapped).contains(new Event("message", Map.of("text", "coupon issued")));
      }

      @Test
      void a_tool_use_only_message_with_no_prose_maps_to_nothing() {
        Message message =
            new Message(
                Role.ASSISTANT,
                List.of(new ToolUseBlock(new ToolCall("c1", "issue_coupon", CALL.arguments()))));

        Optional<Event> mapped = TurnEventSse.of(new TurnEvent.AssistantSaid(message));

        assertThat(mapped).isEmpty();
      }

      @Test
      void a_message_with_only_blank_text_blocks_maps_to_nothing() {
        Message message = new Message(Role.ASSISTANT, List.of(new TextBlock("   ")));

        Optional<Event> mapped = TurnEventSse.of(new TurnEvent.AssistantSaid(message));

        assertThat(mapped).isEmpty();
      }

      @Test
      void prose_alongside_tool_use_still_maps_since_the_joined_text_is_non_blank() {
        List<ContentBlock> content =
            List.of(
                new TextBlock("checking that for you"),
                new ToolUseBlock(new ToolCall("c1", "issue_coupon", CALL.arguments())));
        Message message = new Message(Role.ASSISTANT, content);

        Optional<Event> mapped = TurnEventSse.of(new TurnEvent.AssistantSaid(message));

        assertThat(mapped).contains(new Event("message", Map.of("text", "checking that for you")));
      }
    }

    @Nested
    class Turn_ended {

      @Test
      void a_completed_ending_maps_to_done_with_just_the_status() {
        Optional<Event> mapped =
            TurnEventSse.of(new TurnEvent.TurnEnded(ConversationStatus.COMPLETE, null));

        assertThat(mapped).contains(new Event("done", Map.of("status", "COMPLETE")));
      }

      @Test
      void a_parked_ending_maps_to_done_named_for_its_status() {
        Optional<Event> mapped =
            TurnEventSse.of(new TurnEvent.TurnEnded(ConversationStatus.PARKED, null));

        assertThat(mapped).contains(new Event("done", Map.of("status", "PARKED")));
      }

      @Test
      void a_failed_ending_carries_the_failure_reason_alongside_the_status() {
        Optional<Event> mapped =
            TurnEventSse.of(new TurnEvent.TurnEnded(ConversationStatus.FAILED, "model refused"));

        assertThat(mapped)
            .contains(
                new Event("done", Map.of("status", "FAILED", "failureReason", "model refused")));
      }
    }
  }

  @Nested
  class Observer {

    @Test
    void the_observer_maps_every_event_through_of_into_the_sink() {
      List<Event> sunk = new ArrayList<>();
      Consumer<Event> sink = sunk::add;
      TurnObserver observer = TurnEventSse.observer(sink);

      List<TurnEvent> variants = oneOfEveryVariant();
      variants.forEach(observer::on);

      List<Event> expected =
          variants.stream().map(TurnEventSse::of).map(Optional::orElseThrow).toList();
      assertThat(sunk).containsExactlyElementsOf(expected);
    }

    @Test
    void the_observer_sinks_nothing_for_a_tool_use_only_assistant_said() {
      List<Event> sunk = new ArrayList<>();
      TurnObserver observer = TurnEventSse.observer(sunk::add);
      Message toolUseOnly =
          new Message(
              Role.ASSISTANT,
              List.of(new ToolUseBlock(new ToolCall("c1", "issue_coupon", CALL.arguments()))));

      observer.on(new TurnEvent.AssistantSaid(toolUseOnly));

      assertThat(sunk).isEmpty();
    }
  }
}
