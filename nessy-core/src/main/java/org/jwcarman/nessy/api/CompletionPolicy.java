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
 * The strongest computation semantics an invocation supports (durable spec §5). Declaration order
 * is capability order — {@code IMMEDIATE ⊂ AWAITABLE ⊂ DURABLE} — so {@code compareTo} is the
 * subset test a registry filter uses (spec §4.3: filtering precedes failing).
 */
public enum CompletionPolicy {
  /** Only computations already completed when returned. */
  IMMEDIATE,
  /** Immediate plus process-local asynchronous completion. */
  AWAITABLE,
  /** Immediate, process-local asynchronous, and durable suspension. */
  DURABLE
}
