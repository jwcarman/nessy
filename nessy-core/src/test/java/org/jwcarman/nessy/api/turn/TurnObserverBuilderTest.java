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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;

class TurnObserverBuilderTest {

  private static final ToolCall CALL =
      new ToolCall("c1", "search", JsonNodeFactory.instance.objectNode());
  private static final ParkToken TOKEN = ParkToken.generate();
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
        new TurnEvent.ToolCallParked(CALL, TOKEN),
        new TurnEvent.AssistantSaid(ASSISTANT_MESSAGE),
        new TurnEvent.TurnEnded(ConversationStatus.COMPLETE, null));
  }

  @Test
  void every_registered_consumer_hears_exactly_its_own_variant() {
    List<String> heard = new ArrayList<>();
    TurnObserver observer =
        TurnObserver.builder()
            .onTextDelta(delta -> heard.add("text:" + delta.text()))
            .onThinkingDelta(delta -> heard.add("thinking:" + delta.text()))
            .onRedactedThinking(redacted -> heard.add("redacted:" + redacted.data()))
            .onToolCallRequested(requested -> heard.add("requested:" + requested.call().name()))
            .onToolCallDecided(decided -> heard.add("decided:" + decided.call().name()))
            .onToolCallCompleted(completed -> heard.add("completed:" + completed.call().name()))
            .onToolCallProgressed(progressed -> heard.add("progressed:" + progressed.message()))
            .onToolCallParked(parked -> heard.add("parked:" + parked.token().value()))
            .onAssistantSaid(said -> heard.add("said:" + said.message().content().size()))
            .onTurnEnded(ended -> heard.add("ended:" + ended.status()))
            .build();

    oneOfEveryVariant().forEach(observer::on);

    assertThat(heard)
        .containsExactly(
            "text:prose",
            "thinking:hmm",
            "redacted:opaque",
            "requested:search",
            "decided:search",
            "completed:search",
            "progressed:halfway",
            "parked:" + TOKEN.value(),
            "said:1",
            "ended:COMPLETE");
  }

  @Test
  void unregistered_variants_stay_silent() {
    List<String> heard = new ArrayList<>();
    TurnObserver textOnly =
        TurnObserver.builder().onTextDelta(delta -> heard.add(delta.text())).build();

    oneOfEveryVariant().forEach(textOnly::on);

    assertThat(heard).containsExactly("prose");
  }

  @Test
  void registering_a_variant_twice_chains_in_registration_order() {
    List<String> heard = new ArrayList<>();
    TurnObserver observer =
        TurnObserver.builder()
            .onTextDelta(delta -> heard.add("first:" + delta.text()))
            .onTextDelta(delta -> heard.add("second:" + delta.text()))
            .build();

    observer.on(new TurnEvent.TextDelta("prose"));

    assertThat(heard).containsExactly("first:prose", "second:prose");
  }

  @Test
  void an_observer_already_built_is_unaffected_by_later_registrations() {
    List<String> heard = new ArrayList<>();
    TurnObserverBuilder builder =
        TurnObserver.builder().onTextDelta(delta -> heard.add("early:" + delta.text()));
    TurnObserver early = builder.build();
    builder.onTextDelta(delta -> heard.add("late:" + delta.text()));

    early.on(new TurnEvent.TextDelta("prose"));

    assertThat(heard).containsExactly("early:prose");
  }

  @Test
  void a_null_consumer_is_rejected() {
    TurnObserverBuilder builder = TurnObserver.builder();

    assertThatThrownBy(() -> builder.onTextDelta(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void a_builder_with_nothing_registered_builds_the_absent_audience() {
    TurnObserver indifferent = TurnObserver.builder().build();

    oneOfEveryVariant().forEach(indifferent::on);

    assertThat(indifferent).isNotNull();
  }
}
