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
package org.jwcarman.nessy.agent.support;

import java.util.ArrayList;
import java.util.List;
import org.jwcarman.nessy.agent.memory.VerbatimMemory;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.spi.Memory;
import org.jwcarman.nessy.spi.Remembrance;

/**
 * Remembers every {@link Remembrance} in order, raw ({@link #facts()}), and delegates the
 * provider-legal reassembly to a {@link VerbatimMemory} so {@link #remembered()} exposes the same
 * reconstructed message sequence {@link #recall()} would hand a model — the shape most tests here
 * actually want to assert on.
 */
public final class RecordingMemory implements Memory {

  private final List<Remembrance> facts = new ArrayList<>();
  private final VerbatimMemory delegate = new VerbatimMemory();

  @Override
  public void remember(Remembrance remembrance) {
    facts.add(remembrance);
    delegate.remember(remembrance);
  }

  @Override
  public Context recall() {
    return delegate.recall();
  }

  /** Every remembered fact, in remember order — raw, one entry per {@link #remember} call. */
  public List<Remembrance> facts() {
    return List.copyOf(facts);
  }

  /** The reassembled message sequence {@link #recall()} would produce. */
  public List<Message> remembered() {
    return delegate.recall().messages();
  }
}
