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
package org.jwcarman.nessy.spike.pekko.cluster;

import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.DisplayName;
import org.jwcarman.nessy.spike.pekko.SpikeModel;
import org.jwcarman.nessy.spike.pekko.SpikeRestartContract;
import org.jwcarman.nessy.spike.pekko.SpikeRuntime;
import org.jwcarman.nessy.spike.pekko.SpikeSweep;

/**
 * THROWAWAY SPIKE, TIER 2: the restart contract through Cluster Sharding, where recreating stalled
 * turns is {@code rememberEntities}' job. The sweep is therefore {@link SpikeSweep#none()} — the
 * same test proves both mechanisms, one per tier.
 */
@DisplayName("A parked turn across a restart (cluster sharded)")
class ShardedRestartTest extends SpikeRestartContract {

  @Override
  protected SpikeRuntime start(SpikeModel model, SpikeSweep sweep) {
    return new ShardedSpikeRuntime(
        ConfigFactory.load("spike-cluster-postgres").resolve(), model, sweep);
  }

  @Override
  protected SpikeSweep sweep() {
    return SpikeSweep.none();
  }
}
