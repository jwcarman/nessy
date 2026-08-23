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
package org.jwcarman.nessy.spi;

import org.jwcarman.nessy.api.message.Context;

/**
 * The message stash for inference context: pre-scoped (§3.5). {@code remember} is not part of any
 * caller's atomic batch — a genuinely foreign store (a vector DB, Redis, a custom schema) is a
 * first-class implementation, not a substrate-batch participant (remembrance spec §1).
 *
 * <p>Three laws govern this SPI (remembrance spec §1):
 *
 * <ol>
 *   <li><b>Append before commit — the CALLER's law.</b> A caller that folds a durable transition
 *       remembers every {@link Remembrance} the fold implies BEFORE committing its own state; a
 *       throwing {@code remember} aborts the attempt before anything commits, so the caller's work
 *       stays pending and naturally retries. This implementation contract does not live here — it
 *       binds callers, not implementors.
 *   <li><b>Remember is idempotent by turn identity — the IMPLEMENTOR's law.</b> Every {@link
 *       Remembrance} carries its own opaque {@link Remembrance#key()}; {@code remember}ing the same
 *       key twice must converge to one remembered fact, and {@link #recall()} must return messages
 *       in the order they were first remembered. At-least-once execution, exactly-once effect.
 *   <li><b>Memory-ahead is benign.</b> Between a caller's own remember and its own commit, this
 *       memory may hold a fact the caller has not yet committed elsewhere. A concurrent {@link
 *       #recall()} may see that fact slightly early — tolerated, since the fact it holds is one
 *       that will commit (durable folds cannot abort).
 * </ol>
 *
 * <p>A no-op {@code remember} is legal.
 */
public interface Memory {
  void remember(Remembrance remembrance);

  Context recall();
}
