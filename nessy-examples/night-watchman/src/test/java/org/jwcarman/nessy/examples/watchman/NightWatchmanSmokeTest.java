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
package org.jwcarman.nessy.examples.watchman;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.observation.ObservationRegistry;
import java.time.LocalTime;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.Harness;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.api.ConversationEvent;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The time-triggered story, proved offline (spec §6): rounds land on ONE conversation, the alarm
 * path executes, and the recall window holds under the real loop. The scheduler itself is not under
 * test — cadence is Spring's own disabled-cron sentinel, and the test drives {@code round}
 * directly; {@code @Scheduled} needs no re-proving.
 */
@SpringBootTest(properties = {"watchman.cadence=-", "watchman.window=6"})
class NightWatchmanSmokeTest {

  /** Heard by the sync ToolFinished listener the test harness declares. */
  private static final List<ConversationEvent.ToolFinished> FINISHED = new CopyOnWriteArrayList<>();

  @Autowired private Watchman watchman;
  @Autowired private Agent<String> agent;

  @Test
  void the_clock_calls_and_one_bounded_conversation_answers() {
    // round 1: check_vitals then all-quiet — the round completes
    assertThat(watchman.round(LocalTime.of(2, 0)).state().status())
        .isEqualTo(ConversationStatus.COMPLETE);
    assertThat(FINISHED)
        .isNotEmpty()
        .extracting(finished -> finished.call().name())
        .contains("check_vitals");

    // round 2: the alarm path executes
    assertThat(watchman.round(LocalTime.of(2, 1)).state().status())
        .isEqualTo(ConversationStatus.COMPLETE);
    assertThat(FINISHED).extracting(finished -> finished.call().name()).contains("raise_alarm");

    // continuity: both rounds live in the SAME conversation's recalled context
    Context afterTwo = agent.contextFor(watchman.conversationId());
    List<String> texts = afterTwo.messages().stream().map(NightWatchmanSmokeTest::textOf).toList();
    assertThat(texts)
        .isNotEmpty()
        .anyMatch(t -> t.contains("It is 02:00"))
        .anyMatch(t -> t.contains("It is 02:01"));

    // the bound: run six more all-quiet rounds; recall stays inside the window of 6
    for (int minute = 2; minute < 8; minute++) {
      watchman.round(LocalTime.of(2, minute));
    }
    Context bounded = agent.contextFor(watchman.conversationId());
    assertThat(bounded.messages()).hasSize(6);
  }

  private static String textOf(Message message) {
    StringBuilder text = new StringBuilder();
    message.content().stream()
        .filter(TextBlock.class::isInstance)
        .map(TextBlock.class::cast)
        .forEach(block -> text.append(block.text()));
    return text.toString();
  }

  /**
   * A harness over the scripted provider, in-memory end to end; wins over the starter's own by
   * {@code @ConditionalOnMissingBean(Harness.class)}, which also keeps the real Anthropic provider
   * from ever being constructed — no key, no network. ObservationRegistry stays an ObjectProvider:
   * no actuator here, so no such bean is guaranteed.
   */
  @TestConfiguration
  static class WatchmanTestConfig {

    @Bean
    Harness harness(ObjectProvider<ObservationRegistry> observations) {
      return Nessy.harness(
          h -> {
            h.provider(new ScriptedWatchProvider()).onToolFinished(FINISHED::add);
            observations.ifAvailable(h::observations);
          });
    }
  }

  /**
   * Serves calls by index: round one is a check_vitals exchange, round two a raise_alarm exchange,
   * every later call a plain all-quiet turn — the chat-web scripted two-turn pattern, stretched
   * across a night of rounds.
   */
  private static final class ScriptedWatchProvider implements ModelProvider {

    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public Set<Capability> capabilities() {
      return Set.of();
    }

    @Override
    public ModelStream stream(ModelRequest request) {
      List<ModelEvent> turn =
          switch (calls.incrementAndGet()) {
            case 1 ->
                List.of(
                    new ModelEvent.ToolUseEmitted(
                        new ToolCall("c1", "check_vitals", noArguments())),
                    new ModelEvent.TurnEnded(StopReason.TOOL_USE, Usage.zero()));
            case 2 ->
                List.of(
                    new ModelEvent.TextChunk("All quiet; vitals inside their bands."),
                    new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()));
            case 3 ->
                List.of(
                    new ModelEvent.ToolUseEmitted(
                        new ToolCall("c2", "raise_alarm", alarmArguments())),
                    new ModelEvent.TurnEnded(StopReason.TOOL_USE, Usage.zero()));
            case 4 ->
                List.of(
                    new ModelEvent.TextChunk("Alarm raised: the bilge is climbing."),
                    new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()));
            default ->
                List.of(
                    new ModelEvent.TextChunk("All quiet."),
                    new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()));
          };
      Iterator<ModelEvent> events = turn.iterator();
      return new ModelStream() {
        @Override
        public Iterator<ModelEvent> iterator() {
          return events;
        }

        @Override
        public void close() {
          // scripted stream holds no resources to release
        }
      };
    }

    private static JsonNode noArguments() {
      return JsonNodeFactory.instance.objectNode();
    }

    private static JsonNode alarmArguments() {
      ObjectNode arguments = JsonNodeFactory.instance.objectNode();
      arguments.put("severity", "high");
      arguments.put("reason", "bilge level climbing three rounds straight");
      return arguments;
    }
  }
}
