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

/**
 * Names one parked wait so a later resume can find it.
 *
 * <p>The token is the correlation contract between a parked call and whatever answers it — whatever
 * a resume names, {@link org.jwcarman.nessy.Agent#resume} looks up. Resume delivery is
 * at-least-once in every real transport (webhooks retry, queues redeliver), and the registry entry
 * survives resolution rather than being consumed by it: a redelivered resume translates the token
 * again, and it is the fold's own is-this-call-still-outstanding check — not a single-use claim on
 * the token — that tells a genuine replay from a live wait, draining a redelivered resolution
 * quietly once its call has already settled instead of replaying the tool.
 */
public record ParkToken(String value) {

  public ParkToken {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("park token must not be blank");
    }
  }

  public static ParkToken generate() {
    return new ParkToken(Identifiers.next());
  }
}
