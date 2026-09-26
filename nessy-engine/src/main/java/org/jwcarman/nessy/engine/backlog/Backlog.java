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
package org.jwcarman.nessy.engine.backlog;

import org.jwcarman.nessy.api.Coalescing;

/**
 * A backlog as the harness manages it, rather than as a policy edits it.
 *
 * <p>{@link Coalescing} is what a {@link org.jwcarman.nessy.api.BacklogPolicy} is handed:
 * everything needed to decide what waiting means -- append, replace, drop, read the lot. Taking is
 * not on it, deliberately. A policy that could take would consume an input the harness never sees,
 * and the turn it should have started would never start.
 *
 * <p>So the one verb a harness needs and a policy must not have lives here. Ending an agent is not
 * one of them: that is a statement against the agent itself, not its backlog, and belongs to
 * whatever owns the agent's own row -- not to this type.
 *
 * @param <I> what a caller hands in
 */
public interface Backlog<I> extends Coalescing<I> {

  /**
   * The next thing waiting, removed as it is read.
   *
   * <p>One statement, so nothing can observe an item both waiting and taken. Its caller holds the
   * agent, so nothing else is looking anyway.
   */
  Pull<I> take();
}
