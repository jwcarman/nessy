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
package org.jwcarman.nessy.api;

import org.jwcarman.nessy.internal.Uuids;

/**
 * Names one parked wait so a later resume can find it.
 *
 * <p>Single-use. Resume delivery is at-least-once in every real transport — webhooks retry, queues
 * redeliver — so the store must reject a second resume against a consumed token, or a duplicate
 * click replays a tool call.
 */
public record ParkToken(String value) {

  public ParkToken {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("park token must not be blank");
    }
  }

  public static ParkToken random() {
    return new ParkToken(Uuids.timeOrdered().toString());
  }
}
