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

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * THROWAWAY SPIKE. The one thing the entity needs from a model: given everything said so far, say
 * the next thing.
 *
 * <p>Asynchronous by contract. The entity must never hold its thread across a model call — the
 * result comes back as a message like everything else — so there is no synchronous variant to be
 * tempted by.
 *
 * <p>Two implementations: {@link ScriptedSpikeModel} for the automated tests (fast, deterministic,
 * no network) and {@link LmStudioSpikeModel} for the live demo.
 */
public interface SpikeModel extends AutoCloseable {

  CompletionStage<SpikeModelReply> reply(List<String> transcript);

  @Override
  void close();
}
