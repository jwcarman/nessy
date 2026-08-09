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

import java.util.UUID;

/** Identifies one conversation. Opaque on purpose: the store chooses what it means. */
public record SessionId(String value) {

  public SessionId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("session id must not be blank");
    }
  }

  public static SessionId random() {
    return new SessionId(UUID.randomUUID().toString());
  }
}
