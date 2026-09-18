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

import org.jwcarman.nessy.api.BacklogItem;

/**
 * What a backlog offers when asked for the next thing to work on.
 *
 * <p>Three answers, and they line up one-to-one with the three events a pull can fold: an
 * observation to be rendered and folded, the end of the agent's life, or nothing to do. That is
 * what keeps the pull executor free of any terminal-agent special case -- it folds whatever it is
 * handed.
 *
 * @param <O> the application's observation type
 */
public sealed interface Pull<O> {

  /**
   * An observation to work on, and the backlog left behind once it is taken.
   *
   * <p>The remainder is returned rather than removed, because taking must be undoable: the fold may
   * refuse the observation if the agent has become busy since the pull was scheduled. A caller that
   * refuses simply never persists {@code remainder}, so there is nothing to put back.
   */
  record Item<O>(BacklogItem<O> item, Backlog<O> remainder) implements Pull<O> {}

  /**
   * The agent is sealed and has nothing left to drain. A sealed backlog offers this forever, so
   * termination cannot be undone by a stray observation arriving late.
   */
  record Pill<O>() implements Pull<O> {}

  /** Open, but empty. Nothing to do right now. */
  record Empty<O>() implements Pull<O> {}
}
