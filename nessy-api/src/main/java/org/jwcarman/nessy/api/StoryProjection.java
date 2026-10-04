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
 * A way to fold an agent's story into one value.
 *
 * <p>The story is given, not its content: each {@link Narrated} says what happened and where, and a
 * projection that needs what was said reads it on purpose. {@link AgentStory#project} reads the
 * story a page at a time, oldest first, and calls {@link #apply} once for each event.
 *
 * <p>An accumulator the projection mutates is fine: {@link #initial()} is called once for each
 * {@code project}, so a projection can be reused.
 *
 * @param <T> what the story is folded into
 */
public interface StoryProjection<T> {

  /** What an empty story folds into; where every fold starts. */
  T initial();

  /**
   * The value after one more event.
   *
   * @param soFar the value after the events before this one
   * @param story the next event, with its position
   */
  T apply(T soFar, Narrated story);
}
