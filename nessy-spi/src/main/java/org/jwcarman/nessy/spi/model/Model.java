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

import org.jwcarman.nessy.api.model.ModelId;

/**
 * A bound handle to one model at one vendor — what a turn actually talks to.
 *
 * <p>Implemented by adapter modules and by nobody else, which is what makes it an SPI. An
 * application never holds one: it names a model with a {@link ModelId} and a provider resolves it,
 * so moving an agent from a cheap model to an expensive one stays a configuration change rather
 * than a code change.
 *
 * <p><b>Failures are exceptions here, not results.</b> A rate limit, a timeout, a context overflow
 * — the consumer of those is the callers code, not the model, so they throw. {@link ModelResult}
 * carries only the outcomes the conversation itself has to account for: a reply, or a refusal.
 *
 * <p><b>It only streams.</b> There is no blocking door beside this one, because a second way to
 * make the same call is a second thing that can behave differently — and because everything that
 * consumes a model already wants the events on the way past: a chat interface paints them, a log
 * records them, a metric counts them. Wanting only the finished message is {@code
 * ModelReplies.drain(model.stream(request), event -> {})}, which is a line of code rather than an
 * interface method every adapter has to be trusted to keep consistent.
 */
public interface Model {

  /** Which model this is, at its vendor. */
  ModelId id();

  /** One call, as it happens. The caller drains it, and draining closes it. */
  ModelStream stream(ModelRequest request);
}
