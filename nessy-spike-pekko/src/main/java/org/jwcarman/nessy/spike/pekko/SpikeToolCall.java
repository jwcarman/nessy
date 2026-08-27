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

import java.util.Objects;

/**
 * THROWAWAY SPIKE. One tool call, as the AGENT sees it.
 *
 * <p><b>Compare this with round 2.</b> It used to carry a {@code SpikeCallPhase} — a sealed
 * hierarchy of {@code AwaitingApproval | Running | Finished | Denied}, with its own transition
 * rules, its own re-fire rules and its own admission matrix. That file is <b>deleted</b>.
 *
 * <p>The lifecycle did not go away; it moved into {@link ToolCallActor}, where it is a BEHAVIOUR
 * rather than a datum. What the agent needs to persist about a call collapsed to: what was asked
 * for, and the answer if one has arrived. {@code outcome == null} means "still in flight", which is
 * the only question the agent ever asks.
 */
public record SpikeToolCall(String id, String tool, String argument, String outcome) {

  public SpikeToolCall {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(tool, "tool must not be null");
    Objects.requireNonNull(argument, "argument must not be null");
  }

  public static SpikeToolCall asked(String id, String tool, String argument) {
    return new SpikeToolCall(id, tool, argument, null);
  }

  public boolean settled() {
    return outcome != null;
  }

  public SpikeToolCall settledWith(String result) {
    return new SpikeToolCall(id, tool, argument, result);
  }
}
