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
package org.jwcarman.nessy.api.computation;

import java.util.Objects;

/**
 * A durable description of what becomes runnable on completion — data, never code (durable spec
 * §11). The data is opaque to this module; registrants encode, their handlers decode.
 */
public record Continuation(String type, String data) {

  public Continuation {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(data, "data must not be null");
  }
}
