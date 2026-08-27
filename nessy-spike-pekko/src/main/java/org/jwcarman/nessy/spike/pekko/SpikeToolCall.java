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

/** THROWAWAY SPIKE. One tool call the model asked for, and where it stands. */
public record SpikeToolCall(String id, String tool, String argument, SpikeCallPhase phase)
    implements SpikeSerializable {

  public SpikeToolCall {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(tool, "tool must not be null");
    Objects.requireNonNull(argument, "argument must not be null");
    Objects.requireNonNull(phase, "phase must not be null");
  }

  public SpikeToolCall in(SpikeCallPhase next) {
    return new SpikeToolCall(id, tool, argument, next);
  }

  /** How this call reads in the transcript once it is settled. */
  public String outcome() {
    return switch (phase) {
      case SpikeCallPhase.Finished(String result) -> tool + " -> " + result;
      case SpikeCallPhase.Denied(String reason) -> tool + " -> denied: " + reason;
      case SpikeCallPhase.AwaitingApproval _, SpikeCallPhase.Running _ -> tool + " -> (unsettled)";
    };
  }
}
