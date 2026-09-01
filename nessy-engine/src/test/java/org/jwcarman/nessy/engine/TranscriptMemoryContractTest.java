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

import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.memory.Memory;
import org.jwcarman.nessy.spi.memory.TranscriptMemory;
import org.jwcarman.nessy.testing.MemoryContractTest;
import org.jwcarman.nessy.testing.TestDatabase;

/**
 * Certifies {@link TranscriptMemory} against {@link MemoryContractTest} — the conformance suite
 * every {@link Memory} owes.
 *
 * <p>{@code TranscriptMemoryTest} covers what is particular to this implementation (substrate keys,
 * codecs, versioning); this covers what any Memory must do regardless of how it stores things.
 */
class TranscriptMemoryContractTest extends MemoryContractTest {

  @Override
  protected Memory freshMemory() {
    return TranscriptMemory.eternal(TestDatabase.fresh(), AgentType.of("watchman"));
  }
}
