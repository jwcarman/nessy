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
package org.jwcarman.nessy.agent;

import java.io.Serial;
import org.jwcarman.nessy.api.tool.ComputationId;

/**
 * A delivery that arrived before the park it answers had folded (approval-lifecycle spec §4): the
 * call is still {@code Pending} (approval) or {@code Running} (tool) for this delivery's own kind.
 * Thrown so Continuum releases the delivery and re-delivers it after the backoff, by which time the
 * fold has committed — released, never acknowledged, so no answer is lost.
 */
final class EarlyDeliveryException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final transient ComputationId delivered;

  EarlyDeliveryException(ComputationId delivered) {
    super("a delivery arrived before its park had folded: " + delivered.value());
    this.delivered = delivered;
  }

  ComputationId delivered() {
    return delivered;
  }
}
