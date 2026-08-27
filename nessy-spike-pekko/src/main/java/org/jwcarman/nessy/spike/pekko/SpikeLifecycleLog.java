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
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * THROWAWAY SPIKE. A static, process-wide note-taker so the tests can see actor lifecycle events
 * that leave no trace in the durable state — an entity stopping, and an entity rehydrating.
 *
 * <p>Exists only because the spike needs to PROVE the park is real (the actor genuinely goes away)
 * rather than merely assert the final answer. Nothing like this belongs in real code.
 */
public final class SpikeLifecycleLog {

  private static final Logger LOG = LoggerFactory.getLogger(SpikeLifecycleLog.class);
  private static final List<String> NOTES = new CopyOnWriteArrayList<>();

  private SpikeLifecycleLog() {}

  public static void note(String agentId, String what) {
    String note = agentId + ": " + what;
    NOTES.add(note);
    LOG.info("[spike] {}", note);
  }

  public static List<String> notes() {
    return List.copyOf(NOTES);
  }

  public static void clear() {
    NOTES.clear();
  }
}
