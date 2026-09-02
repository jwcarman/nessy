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
package org.jwcarman.nessy.memory.summarizing;

import org.junit.jupiter.api.DisplayName;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.memory.Memory;
import org.jwcarman.nessy.api.model.ModelId;
import org.jwcarman.nessy.spi.memory.TranscriptMemory;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;
import org.jwcarman.nessy.testing.MemoryContractTest;
import org.jwcarman.nessy.testing.TestDatabase;

/**
 * A summarizing memory is a {@link Memory}, and this is what says so.
 *
 * <p>Thresholds are set high enough that nothing compresses: the contract is about recalling what
 * was remembered and forgetting it on request, and a summary in the middle of that would be testing
 * something else. What compressing does is {@link SummarizingMemoryTest}'s business.
 */
@DisplayName("A summarizing memory, as a memory")
class SummarizingMemoryContractTest extends MemoryContractTest {

  private static final AgentType TYPE = AgentType.of("contract");

  @Override
  protected Memory freshMemory() {
    var database = TestDatabase.fresh();
    return SummarizingMemory.create(
        config ->
            config
                .transcript(TranscriptMemory.eternal(database, TYPE))
                .dataSource(database)
                .agentType(TYPE)
                .model(new NeverAsked())
                .executor(Runnable::run)
                .summarizeAfter(1_000_000)
                .keepVerbatim(1_000));
  }

  /** Compressing is out of scope here, so being called at all is the failure. */
  private static final class NeverAsked implements Model {

    @Override
    public ModelId id() {
      return ModelId.of("never-asked");
    }

    @Override
    public ModelStream stream(ModelRequest request) {
      throw new IllegalStateException("the contract test should never trigger compression");
    }
  }
}
