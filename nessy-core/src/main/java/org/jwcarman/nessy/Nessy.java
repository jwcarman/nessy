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
package org.jwcarman.nessy;

/**
 * The front door. {@code Nessy.agent()} is the fast path for a single agent; {@code
 * Nessy.harness()} is where an application with more than one agent starts, since a {@link Harness}
 * is the infrastructure they share.
 */
public final class Nessy {

  private Nessy() {}

  /**
   * Sugar for the common, single-agent case: an implicit default {@link Harness} — every knob at
   * its default — seeding a fresh {@link AgentBuilder}. Equivalent to {@code
   * Nessy.harness().build().agent()}.
   */
  public static AgentBuilder<String> agent() {
    return harness().build().agent();
  }

  /**
   * Starts assembling the infrastructure — provider, store, transcript, hub, observations, mapper —
   * that every agent built from the resulting {@link Harness} will share.
   */
  public static HarnessBuilder harness() {
    return new HarnessBuilder();
  }
}
