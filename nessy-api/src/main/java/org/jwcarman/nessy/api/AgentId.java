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

import java.util.Objects;
import java.util.UUID;

/**
 * Which agent this is.
 *
 * <p>Chosen by the application, not by the engine: an agent is usually the thing an application
 * already has an identity for -- a conversation, a device, a ticket -- and minting a second one
 * here would only create something to keep in step.
 *
 * <p>A value type rather than a raw {@link UUID} so it cannot be passed where some other id was
 * meant. Nothing in this system has one id.
 */
public record AgentId(UUID value) {

  public AgentId {
    Objects.requireNonNull(value, "value must not be null");
  }

  /** For an agent whose identity is nobody else's business. */
  public static AgentId random() {
    return new AgentId(UUID.randomUUID());
  }
}
