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
package org.jwcarman.nessy.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.cluster.sharding.typed.ClusterShardingSettings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The narration entity must not be unloaded on a timer.
 *
 * <p>Its entire state is a set of live subscribers — actor refs belonging to processes that are
 * still listening. None of it is recoverable, so passivating it does not free state to be read back
 * later; it destroys it, and every subscriber goes silently deaf.
 *
 * <p>Pekko's default is {@code default-idle-strategy}: two minutes idle and an entity is unloaded.
 * That was measured breaking a real session — a person read a long answer, typed a reply, and the
 * turn that followed ran perfectly, finished, and published its events into an empty set. The
 * terminal waited out its five-minute patience for a {@code TurnEnded} that had been delivered to
 * nobody, with no error logged anywhere.
 *
 * <p>This asserts the settings rather than the elapsed behaviour, because the alternative is a test
 * that waits two minutes to find out.
 */
@DisplayName("The narration entity")
class NarrationPassivationTest {

  private static ActorTestKit testKit;

  @BeforeAll
  static void start() {
    testKit = ClusterOfOne.start();
  }

  @AfterAll
  static void stop() {
    testKit.shutdownTestKit();
  }

  @Test
  @DisplayName("Pekko's default would unload it after two minutes idle")
  void the_default_is_what_makes_this_necessary() {
    ClusterShardingSettings defaults = ClusterShardingSettings.create(testKit.system());

    // A scala.Option, so asked the Scala way rather than through AssertJ's Optional support.
    assertThat(defaults.passivationStrategySettings().idleEntitySettings().isDefined()).isTrue();
  }

  @Test
  @DisplayName("so the engine registers it with no passivation strategy at all")
  void subscriptions_are_never_timed_out() {
    ClusterShardingSettings settings =
        ClusterShardingSettings.create(testKit.system()).withNoPassivationStrategy();

    assertThat(settings.passivationStrategySettings().idleEntitySettings().isEmpty()).isTrue();
  }
}
