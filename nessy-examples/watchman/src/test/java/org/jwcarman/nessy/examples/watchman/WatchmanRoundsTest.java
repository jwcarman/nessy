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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.EmptyInput;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.TellOutcome;
import org.slf4j.LoggerFactory;

@DisplayName("The watchman's rounds")
class WatchmanRoundsTest {

  /** Answers every tell with one outcome, and remembers what it was told. */
  private static final class AnsweringHarness implements QueuedHarness<EmptyInput> {
    private final TellOutcome answer;
    private final List<AgentId> told = new ArrayList<>();

    AnsweringHarness(TellOutcome answer) {
      this.answer = answer;
    }

    @Override
    public TellOutcome tell(AgentId agentId, EmptyInput input) {
      told.add(agentId);
      return answer;
    }

    @Override
    public void terminate(AgentId agentId) {
      // The rounds never terminate the watchman; nothing here is asked to.
    }
  }

  /** What WatchmanRounds itself logged during one test, and nothing any other thread wrote. */
  private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

  private final Logger roundsLogger = (Logger) LoggerFactory.getLogger(WatchmanRounds.class);

  @BeforeEach
  void listenToTheRounds() {
    logged.start();
    roundsLogger.setLevel(Level.DEBUG);
    roundsLogger.addAppender(logged);
  }

  @AfterEach
  void stopListening() {
    roundsLogger.detachAppender(logged);
    logged.stop();
  }

  private List<ILoggingEvent> warnings() {
    return logged.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
  }

  @Nested
  @DisplayName("When the watchman has been terminated")
  class WhenTerminated {

    @Test
    void a_round_warns_that_the_watchman_will_do_no_more_rounds() {
      AnsweringHarness harness = new AnsweringHarness(new TellOutcome.Terminated());
      WatchmanRounds rounds = new WatchmanRounds(harness);

      rounds.round();

      assertThat(harness.told).containsExactly(Watchman.AGENT);
      assertThat(warnings())
          .singleElement()
          .extracting(ILoggingEvent::getFormattedMessage)
          .asString()
          .contains("the watchman agent has been terminated and will do no more rounds");
    }
  }

  @Nested
  @DisplayName("When the watchman took the nudge")
  class WhenAccepted {

    @Test
    void a_round_does_not_warn() {
      AnsweringHarness harness = new AnsweringHarness(new TellOutcome.Accepted());
      WatchmanRounds rounds = new WatchmanRounds(harness);

      rounds.round();

      assertThat(harness.told).containsExactly(Watchman.AGENT);
      assertThat(logged.list)
          .as("the round logged something, so the listener is attached")
          .isNotEmpty();
      assertThat(warnings()).isEmpty();
    }
  }
}
