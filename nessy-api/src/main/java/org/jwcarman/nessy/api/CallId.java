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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The model's own name for ONE tool call it asked for.
 *
 * <p>Unique within a single response and no further: two turns can each produce a {@code "call_1"},
 * which is why nothing is ever keyed on a call id alone. Pair it with the {@link TurnId} to name a
 * call across time, and with the {@link AgentType} and {@link AgentId} to name it across agents.
 *
 * <p><b>This one arrives from outside.</b> Unlike every other identifier here it is a provider's
 * string, read off the wire. It is checked on the way in for exactly that reason: an unchecked
 * value from a vendor reaches a primary key, and the failure surfaces in an INSERT rather than at
 * the parser that accepted it. A call id that cannot pass this guard means something upstream is
 * already wrong, and failing loudly beside the response that carried it is the legible place to say
 * so.
 */
public record CallId(@JsonValue String value) {

  public CallId {
    value = Identifier.checked("call id", value);
  }

  @JsonCreator
  public static CallId of(String value) {
    return new CallId(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
