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
import java.util.Optional;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.IdempotencyKey;

/**
 * What an agent's story refers to but does not carry: the words said, and what tools returned.
 *
 * <p>The story never carries content. Every read here is addressed explicitly, by a turn, by a call
 * or by a position, and is a separate read from the story itself. Content can expire on a different
 * schedule from the story, so an entry that is gone is a fault, and is reported as one.
 */
public interface StoryContent {

  /**
   * What one turn was given and said: its input, what the model wrote on the way, and its answer.
   *
   * @param turn the turn, which is the {@link Seq} of the event that started it
   * @throws IllegalArgumentException if the agent's story has no such turn
   * @throws IllegalStateException if content the story refers to is no longer stored
   */
  TurnContent turn(TurnId turn);

  /**
   * What a call returned, found by its key.
   *
   * <p>Looked up inside this agent's story. A key that is not in it, or a call that did not
   * succeed, has no result.
   *
   * @throws NullPointerException if {@code key} is null
   * @throws IllegalStateException if the content of the result is no longer stored
   */
  Optional<List<Block.ToolResultContent>> result(IdempotencyKey key);

  /**
   * Up to {@code limit} successful results after {@code after}, oldest first.
   *
   * <p>Each result is at the position of the event that recorded the success. Calls that failed or
   * were refused have none, and are skipped.
   *
   * @param after the position to read after; {@link Seq#NONE} reads from the start
   * @param limit how many results at most; above 1,000 it is treated as 1,000
   * @throws NullPointerException if {@code after} is null
   * @throws IllegalArgumentException if {@code limit} is not positive
   * @throws IllegalStateException if content a result refers to is no longer stored
   */
  List<CallResult> results(Seq after, int limit);
}
