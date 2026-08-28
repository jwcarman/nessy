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
import org.junit.jupiter.api.Test;

/**
 * The engine module's ground floor: Pekko is on the classpath, an actor system starts, and the
 * testkit can hand one over. Everything in this module is built on top of that being true.
 */
class ModuleWiringTest {

  @Test
  void theEngineModuleHasAnActorSystemAvailableToIt() {
    ActorTestKit kit = ActorTestKit.create();
    try {
      assertThat(kit.system().name()).isNotBlank();
    } finally {
      kit.shutdownTestKit();
    }
  }
}
