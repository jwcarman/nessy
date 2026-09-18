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
package org.jwcarman.nessy.api.tool;

import java.util.Objects;

/**
 * Where a deferred answer goes.
 *
 * <p>Handed to whatever will eventually answer -- a person, a queue, a webhook -- and handed back
 * with the answer so the engine can match it to the call still waiting. It is the only thing
 * connecting the two, which is why a blank one is refused: a token nobody can match is a call
 * nobody can finish, and the agent waits until its deadline for an answer that had nowhere to land.
 */
public record ReplyToken(String value) {

  public ReplyToken {
    Objects.requireNonNull(value, "value must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException("value must not be blank");
    }
  }
}
