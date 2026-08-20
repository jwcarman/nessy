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
package org.jwcarman.nessy.durable;

import java.util.Objects;

/**
 * The two legal answers to an atomic await (durable spec §12): the outcome is already in hand, or
 * the continuation is durably registered before completion could proceed. There is no third state.
 */
public sealed interface AwaitResult {

  record Registered() implements AwaitResult {}

  record AlreadyCompleted(Outcome outcome) implements AwaitResult {
    public AlreadyCompleted {
      Objects.requireNonNull(outcome, "outcome must not be null");
    }
  }
}
