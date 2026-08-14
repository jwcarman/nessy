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

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.Conversation;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The rounds (spec §5): the clock is the caller. Every cron firing tells the SAME conversation "do
 * your rounds" — one continuous conversation is what lets the agent see trends across firings, and
 * {@link WindowedMemory} is what keeps that conversation from growing the model call. Spring's
 * default single-threaded scheduler serializes rounds: a slow round delays the next rather than
 * overlapping it. The package-private overload is the test's entry point — the scheduler is only a
 * trigger.
 */
@Component
public class Watchman {

  private static final Logger LOGGER = LoggerFactory.getLogger(Watchman.class);
  private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

  private final Conversation<String> conversation;

  public Watchman(Agent<String> agent) {
    this.conversation = agent.converse();
  }

  public ConversationId conversationId() {
    return conversation.conversationId();
  }

  @Scheduled(cron = "${watchman.cadence:0 * * * * *}")
  public void round() {
    // Explicit zone (S8688): the watchman reports the machine's own local time, so
    // ZoneId.systemDefault() names the zone the implicit no-arg now() would silently assume.
    round(LocalTime.now(ZoneId.systemDefault()));
  }

  RunOutcome round(LocalTime time) {
    String prompt = "It is " + CLOCK.format(time) + " — do your rounds.";
    LOGGER.info("round begins: {}", prompt);
    StringBuilder said = new StringBuilder();
    RunOutcome outcome =
        conversation.tell(
            prompt,
            event -> {
              switch (event) {
                case TurnEvent.TextDelta(String text) -> said.append(text);
                case TurnEvent.ToolCallRequested(ToolCall call) ->
                    LOGGER.info("tool: {}", call.name());
                // deliberate extender-tolerance default (chat-cli's discipline): the log ignores
                // variants it has no rendering for.
                default -> {}
              }
            });
    if (!said.isEmpty()) {
      LOGGER.info("watchman says: {}", said);
    }
    LOGGER.info("round ends: {}", outcome.state().status());
    return outcome;
  }
}
