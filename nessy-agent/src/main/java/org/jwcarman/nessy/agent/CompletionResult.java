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
package org.jwcarman.nessy.agent;

/**
 * Whether this completion attempt was the ownership transfer (durable-deliveries spec §3): {@code
 * TRANSFERRED} means this call durably moved the pending computation into its outbox delivery;
 * {@code ALREADY_DONE} means the computation was absent — completed earlier by another racer, or
 * never created at all. The two causes are indistinguishable and equally benign under at-least-once
 * result delivery (ruling 6, reversed): completion never creates records.
 *
 * <p>Package-private (computation-identity spec §2 addendum, the whittle ruling): {@link
 * SubstrateComputations#complete} is the one public-facing return, called from every package that
 * reaches a backend, but never captured into a named local outside {@code org.jwcarman.nessy.agent}
 * — no desk's public signature carries it.
 */
enum CompletionResult {
  TRANSFERRED,
  ALREADY_DONE
}
