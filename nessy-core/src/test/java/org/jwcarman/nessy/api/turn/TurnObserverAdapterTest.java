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

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;

class TurnObserverAdapterTest {

  private static final ToolCall CALL =
      new ToolCall("c1", "search", JsonNodeFactory.instance.objectNode());
  private static final Message ASSISTANT_MESSAGE =
      Message.assistant(List.of(new TextBlock("hello")));

  private static List<TurnEvent> oneOfEveryVariant() {
    return List.of(
        new TurnEvent.TextDelta("prose"),
        new TurnEvent.ThinkingDelta("hmm"),
        new TurnEvent.RedactedThinking("opaque"),
        new TurnEvent.ToolCallRequested(CALL),
        new TurnEvent.ToolCallDecided(CALL, Decision.allow()),
        new TurnEvent.ToolCallCompleted(CALL, ToolResult.ok("done")),
        new TurnEvent.ToolCallProgressed(CALL, "halfway"),
        new TurnEvent.AssistantSaid(ASSISTANT_MESSAGE),
        new TurnEvent.TurnEnded(null));
  }

  @Test
  void every_variant_routes_to_its_own_hook() {
    List<String> routed = new ArrayList<>();
    TurnObserver observer =
        new TurnObserverAdapter() {
          @Override
          protected void onTextDelta(TurnEvent.TextDelta event) {
            routed.add("text:" + event.text());
          }

          @Override
          protected void onThinkingDelta(TurnEvent.ThinkingDelta event) {
            routed.add("thinking:" + event.text());
          }

          @Override
          protected void onRedactedThinking(TurnEvent.RedactedThinking event) {
            routed.add("redacted:" + event.data());
          }

          @Override
          protected void onToolCallRequested(TurnEvent.ToolCallRequested event) {
            routed.add("requested:" + event.call().name());
          }

          @Override
          protected void onToolCallDecided(TurnEvent.ToolCallDecided event) {
            routed.add("decided:" + event.call().name());
          }

          @Override
          protected void onToolCallCompleted(TurnEvent.ToolCallCompleted event) {
            routed.add("completed:" + event.call().name());
          }

          @Override
          protected void onToolCallProgressed(TurnEvent.ToolCallProgressed event) {
            routed.add("progressed:" + event.message());
          }

          @Override
          protected void onAssistantSaid(TurnEvent.AssistantSaid event) {
            routed.add("said:" + event.message().content().size());
          }

          @Override
          protected void onTurnEnded(TurnEvent.TurnEnded event) {
            routed.add("ended:" + (event.failed() ? "FAILED" : "COMPLETE"));
          }
        };

    oneOfEveryVariant().forEach(observer::on);

    assertThat(routed)
        .containsExactly(
            "text:prose",
            "thinking:hmm",
            "redacted:opaque",
            "requested:search",
            "decided:search",
            "completed:search",
            "progressed:halfway",
            "said:1",
            "ended:COMPLETE");
  }

  @Test
  void a_subclass_that_overrides_nothing_ignores_the_whole_turn_in_silence() {
    TurnObserver indifferent = new TurnObserverAdapter() {};

    oneOfEveryVariant().forEach(indifferent::on);

    assertThat(indifferent).isNotNull();
  }

  @Test
  void a_selective_narrator_hears_only_what_it_overrode() {
    List<String> heard = new ArrayList<>();
    TurnObserver textOnly =
        new TurnObserverAdapter() {
          @Override
          protected void onTextDelta(TurnEvent.TextDelta event) {
            heard.add(event.text());
          }
        };

    oneOfEveryVariant().forEach(textOnly::on);

    assertThat(heard).containsExactly("prose");
  }
}
