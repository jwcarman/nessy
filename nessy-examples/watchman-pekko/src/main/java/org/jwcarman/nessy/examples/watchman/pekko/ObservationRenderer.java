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
package org.jwcarman.nessy.examples.watchman.pekko;

import java.util.List;
import org.jwcarman.nessy.api.message.ContentBlock;

/**
 * How an observation of type {@code O} becomes what the model actually reads.
 *
 * <p>Rendering happens at DRAIN, not at ingest — see {@link Backlog}'s javadoc for why the backlog
 * holds the observation itself rather than pre-rendered content blocks. That design is only real if
 * a renderer change reaches an observation that is already sitting in the backlog, which is exactly
 * what supplying this through {@link AgentActor.Dependencies} rather than baking it into {@link
 * Backlogs#ingest} buys: the backlog never sees a renderer, and {@link AgentActor} calls this one
 * only when a turn actually starts.
 */
@FunctionalInterface
public interface ObservationRenderer<O> {

  List<ContentBlock> render(O observation);
}
