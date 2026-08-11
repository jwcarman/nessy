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
package org.jwcarman.nessy.spi.model;

/**
 * The request cannot fit the model's context window — a conversation-shaped, permanent rejection.
 * Providers throw this (in place of their raw 400) when the wire says the prompt is too long; the
 * model-call executor converts it into the {@code ModelCallFailed} fact. Every other provider
 * failure stays an ordinary exception: transient, re-drivable, telemetry's business.
 */
public class ContextOverflowException extends RuntimeException {

  public ContextOverflowException(String message) {
    super(message);
  }
}
