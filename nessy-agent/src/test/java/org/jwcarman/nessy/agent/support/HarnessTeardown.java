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
import org.jwcarman.nessy.agent.Harness;

/**
 * Fix round 1, item 5: every {@link Harness} now owns a live delivery-worker heartbeat (harness-
 * first spec §4) — including the throwaway ones {@code TestAgents} and {@code AgentFixture} build
 * per test method. Neither is a JUnit-managed fixture (both are plain local variables), so there is
 * no single instance an {@code @AfterEach} can reach directly; this is the choke point instead —
 * {@code TestAgents.harness(...)} and {@code AgentFixture}'s constructor both {@link #track} the
 * harness they just built, and a test class pairs one {@code @AfterEach} calling {@link
 * #shutdownAllTracked()} with every test method that (transitively) builds one, reclaiming the
 * accumulating heartbeat threads without touching each test method's body.
 */
public final class HarnessTeardown {

  private static final List<Harness<?>> TRACKED = new ArrayList<>();

  private HarnessTeardown() {}

  public static synchronized void track(Harness<?> harness) {
    TRACKED.add(harness);
  }

  /** Shuts down every harness tracked since the last call, then forgets them. */
  public static synchronized void shutdownAllTracked() {
    TRACKED.forEach(Harness::shutdown);
    TRACKED.clear();
  }
}
