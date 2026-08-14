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

import org.junit.jupiter.api.Test;

/**
 * The synthetic vitals, pinned: deterministic under a seed (so the demo and the tests tell the same
 * story) and biased so the bilge genuinely rises — the drift is what gives the demo its arc (spec
 * §2).
 */
class EngineRoomTest {

  @Test
  void the_same_seed_tells_the_same_story() {
    EngineRoom first = new EngineRoom(42L);
    EngineRoom second = new EngineRoom(42L);
    for (int i = 0; i < 10; i++) {
      assertThat(first.read()).isEqualTo(second.read());
    }
  }

  @Test
  void the_bilge_rises_because_the_walk_is_biased() {
    EngineRoom engineRoom = new EngineRoom(42L);
    double start = engineRoom.read().bilgeLevelCm();
    double last = start;
    for (int i = 0; i < 19; i++) {
      last = engineRoom.read().bilgeLevelCm();
    }
    // Bias is +3.5/step against noise sd 1.5: after 20 steps the climb dominates decisively.
    assertThat(last).isGreaterThan(start + 30.0);
  }

  @Test
  void every_vital_stays_inside_its_physical_clamp() {
    EngineRoom engineRoom = new EngineRoom(7L);
    for (int i = 0; i < 200; i++) {
      EngineRoom.Vitals vitals = engineRoom.read();
      assertThat(vitals.boilerPressurePsi()).isBetween(150.0, 260.0);
      assertThat(vitals.bilgeLevelCm()).isBetween(0.0, 100.0);
      assertThat(vitals.hullStressMpa()).isBetween(20.0, 90.0);
    }
  }
}
