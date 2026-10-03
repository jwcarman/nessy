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
import java.util.UUID;

/**
 * The key that makes doing a call's work again safe: one per call, the same every time that call is
 * asked about or run.
 *
 * <p>Made once, when the model's request is recorded, and stored with the call. So it stays the
 * same when a tool is retried, when an approval is asked again after a restart, and when a call
 * moves from being approved to being run: the {@link ApprovalRequest} and the {@link
 * ToolCallRequest} for one call carry the same key. It is unique across every agent an application
 * runs, so it can key a table on its own, or go to a system that takes an idempotency key, such as
 * a payment API.
 *
 * <p>Not the {@link CallId}: that is what the model called the call, unique only within one of its
 * replies, and sent back to the vendor. This is Nessy's own, and never leaves the application.
 */
public record IdempotencyKey(@JsonValue UUID value) {

  public IdempotencyKey {
    Objects.requireNonNull(value, "idempotency key must not be null");
  }

  /** Read back from the bare UUID it was written as, as {@link CallId} is from its string. */
  @JsonCreator
  public static IdempotencyKey of(UUID value) {
    return new IdempotencyKey(value);
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
