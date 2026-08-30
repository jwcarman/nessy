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

import org.jwcarman.nessy.api.message.UserMessage;

/**
 * How an observation becomes something a model can read.
 *
 * <p>An observation is whatever the application's vocabulary says happened; the model only reads
 * messages. Rendering to a {@link UserMessage} rather than to text is what lets an observation
 * carry an image without a second door — and it is exactly what {@code Memory.remember} takes, so
 * what a renderer produces reaches the transcript without anything in between reshaping it.
 *
 * <p><b>A renderer never declines.</b> By the time one runs, the observation has already survived
 * the {@code BacklogCoalescer}, which is where dropping, merging, and superseding happen — an
 * observation that should not become a turn is refused THERE, by not being returned into the
 * backlog. A renderer that could also decline would be the same judgment made a stage too late, at
 * the point where it silently breaks the turn lifecycle: nothing dispatches, so no turn ever ends,
 * so anything waiting on that turn waits forever.
 *
 * @param <O> the observation type
 */
@FunctionalInterface
public interface ObservationRenderer<O> {

  UserMessage render(O observation);
}
