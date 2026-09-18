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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;

/**
 * What a tool is called, as the model is offered it and as the model asks for it.
 *
 * <p>Both directions, and the same string: a name is published in an offer and comes back in a
 * call. A name that arrives in a call need not still be bound to anything -- configuration changes,
 * and models misremember -- so nothing here assumes one resolves.
 *
 * <p>A type rather than a string because it travels beside {@link CallId} through the same
 * signatures. {@code Approve(requestSeq, callId, toolName)} takes two strings that mean entirely
 * different things, and a compiler that sees only {@code String} cannot tell them apart or stop
 * them being swapped.
 *
 * <p>Deliberately unvalidated beyond being present. Providers differ on what a name may contain,
 * and a rule invented here would reject a tool some vendor accepts -- or, worse, accept one it
 * rejects and fail at the wire.
 */
public record ToolName(@JsonValue String value) {

  public ToolName {
    Objects.requireNonNull(value, "tool name must not be null");
    if (value.isBlank()) {
      // Says what it is rather than what its component is called. A wrapper that
      // reported "value must not be blank" would leave a caller with two strings in the
      // same constructor and no idea which one it meant.
      throw new IllegalArgumentException("tool name must not be blank");
    }
  }

  /**
   * Read back from the bare string it was written as.
   *
   * <p>{@code @JsonValue} and this together keep the stored shape exactly what it was before this
   * became a type: {@code "id":"call_1"} rather than {@code "id":{"value":"call_1"}}. Wrapping a
   * value that is already written down is a migration unless the wrapper is transparent, and there
   * is no reason for it not to be -- what a provider called something is a string, and it is stored
   * as one.
   */
  @JsonCreator
  public static ToolName of(String value) {
    return new ToolName(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
