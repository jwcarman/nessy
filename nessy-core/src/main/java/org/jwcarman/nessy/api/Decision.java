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
 * The answer to an approval question.
 *
 * <p>Lives in {@code core} rather than {@code approval} so {@link Event} can reference it without
 * the core package depending on the approval package.
 */
public sealed interface Decision {

  /** Run it. */
  record Allow() implements Decision {
    private static final Allow INSTANCE = new Allow();
  }

  /** Do not run it. The reason goes into context so the model can adapt. */
  record Deny(String reason) implements Decision {}

  /** The one instance of {@link Allow}; the record has no state, so one is enough. */
  static Decision allow() {
    return Allow.INSTANCE;
  }
}
