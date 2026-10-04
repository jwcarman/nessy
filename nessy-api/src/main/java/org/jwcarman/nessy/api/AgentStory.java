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

import java.util.List;

/** One agent's story, as stored. */
public interface AgentStory {

  /**
   * Up to {@code limit} story events after {@code after}, oldest first, each with its position.
   *
   * <p>A story event read here equals the one a live listener heard as it was stored: the same
   * event, at the same {@link Seq}, written at the same instant.
   *
   * @param after the position to read after; {@link Seq#NONE} reads from the start
   * @param limit how many events at most; above 1,000 it is treated as 1,000
   * @throws NullPointerException if {@code after} is null
   * @throws IllegalArgumentException if {@code limit} is not positive
   */
  List<Narrated> replay(Seq after, int limit);

  /**
   * Folds the whole story, oldest first, into one value.
   *
   * <p>The story is read a page at a time, so nothing holds all of it at once. An exception the
   * projection throws reaches the caller unchanged.
   *
   * <p>Reads the agent's whole story each time it is called, so its cost grows with the story; do
   * not call it on a hot path, such as a poll or every request.
   *
   * @param <T> what the projection folds the story into
   * @param projection how to fold the story
   * @return the projection's {@link StoryProjection#initial()} when the story is empty
   * @throws NullPointerException if {@code projection} is null
   */
  <T> T project(StoryProjection<T> projection);

  /**
   * What this story refers to: the content behind its events.
   *
   * <p>The story itself carries none; see {@link StoryContent}.
   */
  StoryContent content();
}
