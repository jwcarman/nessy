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
 * <p>Three answers, and they line up one-to-one with what a caller can do about them: something to
 * work on, the end of the agent's life, or nothing right now. That is what keeps the code that
 * drives an agent free of any terminated-agent special case -- it acts on whatever it is handed.
 *
 * @param <I> the application's input type
 */
public sealed interface Pull<I> {

  /** An input to work on. Taken: it is no longer waiting. */
  record Item<I>(BacklogItem<I> item) implements Pull<I> {}

  /**
   * The agent has been ended and has nothing left to drain.
   *
   * <p><b>Why this is not simply an empty backlog.</b> Terminating cannot be delivered to an agent
   * in the middle of a turn -- the fold takes it only from idle -- so it has to survive until the
   * turn it interrupted is over. What happens instead is that the backlog is emptied and the agent
   * is marked, and this is what every read afterwards answers. The next time the agent is idle and
   * asks for work, this is the work: end.
   *
   * <p>Offered forever, so a stray input arriving late cannot undo a termination.
   */
  record Pill<I>() implements Pull<I> {}

  /** Nothing waiting, and the agent is still accepting. How an agent goes quiet. */
  record Empty<I>() implements Pull<I> {}
}
