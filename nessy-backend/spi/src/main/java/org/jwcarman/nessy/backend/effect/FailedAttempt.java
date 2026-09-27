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
package org.jwcarman.nessy.backend.effect;

import java.util.Objects;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.inference.Failure;

/**
 * What one attempt learned before it was tried again.
 *
 * <p>Kept because the attempt cost something. A retried call reaches the fold once, when it finally
 * settles, so without this the tokens spent on the attempts before it are recorded nowhere -- and
 * that is the reading a budget most needs, since a turn that is thrashing spends precisely where
 * nobody is looking.
 *
 * <p><b>Not an event.</b> This is the carrier between the row and the fold; what the fold writes
 * down is an {@code AgentEvent.InferenceAttempted} per attempt, with these two values flattened
 * into it. The two exist separately because one is a durable row's business and the other is the
 * story's.
 *
 * <p>Only a failure worth repeating ever becomes one of these. An attempt that ended the work has
 * nothing to accumulate: it is the outcome.
 */
public record FailedAttempt(Failure failure, Usage usage) {

  public FailedAttempt {
    Objects.requireNonNull(failure, "failure must not be null");
    usage = usage == null ? Usage.unreported() : usage;
  }
}
