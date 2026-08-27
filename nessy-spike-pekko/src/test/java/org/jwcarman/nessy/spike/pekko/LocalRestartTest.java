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
package org.jwcarman.nessy.spike.pekko;

import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.DisplayName;

/**
 * THROWAWAY SPIKE, TIER 1: the restart contract on a single node, where recreating unfinished turns
 * is {@link SpikeSweep}'s job rather than {@code rememberEntities}'.
 */
@DisplayName("A parked turn across a restart (single node)")
class LocalRestartTest extends SpikeRestartContract {

  static final String URL = "jdbc:postgresql://localhost:5432/watchman?currentSchema=pekko_spike";

  @Override
  protected SpikeRuntime start(SpikeModel model, SpikeSweep sweep) {
    return new LocalSpikeRuntime(ConfigFactory.load("spike-postgres").resolve(), model, sweep);
  }

  @Override
  protected SpikeSweep sweep() {
    return SpikeSweep.overPostgres(URL, "watchman", "watchman");
  }
}
