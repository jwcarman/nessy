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

/**
 * Whether the reaper may redispatch an overdue durable tool invocation with the same {@code
 * ToolInvocationId} (durable-deliveries spec §6). Declared at tool registration ({@link
 * ToolConfig#retrySemantics(RetrySemantics)}); the default is {@link #NON_RETRYABLE}.
 *
 * <p>{@link #RETRYABLE} is the tool author's own safety assertion, not a fact Nessy can verify:
 * declaring it says redispatching this tool's external side effect with the same invocation
 * identity is safe — by idempotence, by dedup on that identity, or by a provider idempotency key
 * the tool derives from it. Nessy guarantees stable identity and durable routing; it never
 * guarantees the external side effect itself runs exactly once. A tool that cannot make that
 * assertion stays {@link #NON_RETRYABLE}: its overdue computation is failed, not retried.
 */
public enum RetrySemantics {
  /** The tool author asserts redispatch with the same {@code ToolInvocationId} is safe. */
  RETRYABLE,
  /** The default: an overdue computation is failed rather than redispatched. */
  NON_RETRYABLE
}
