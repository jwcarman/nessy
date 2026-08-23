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
package org.jwcarman.nessy.spi.approval;

import org.jwcarman.nessy.api.tool.ComputationId;

/** How one {@code RequireApproval} got adjudicated (spec §4.3 amendment). */
public sealed interface Adjudication {

  /** Run it — the decision fact says yes. */
  record Granted() implements Adjudication {}

  /** Do not run it; the reason goes in-band so the model reads it and reacts. */
  record Refused(String reason) implements Adjudication {

    public Refused {
      if (reason == null || reason.isBlank()) {
        throw new IllegalArgumentException("reason must not be blank");
      }
    }
  }

  /** The question is open in a durable computation; the call suspends. */
  record Suspended(ComputationId computation) implements Adjudication {}
}
