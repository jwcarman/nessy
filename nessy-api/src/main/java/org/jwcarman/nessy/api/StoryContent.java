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
import java.util.stream.Stream;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import tools.jackson.databind.JsonNode;

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
   * <p>Reads the story from its start until it finds the call, a page at a time, so its cost grows
   * with the story.
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
   * <p>Reads forward from {@code after} until it has {@code limit} results, so a long story with
   * few successful calls is read to its end.
   *
   * @param after the position to read after; {@link Seq#NONE} reads from the start
   * @param limit how many results at most; above 1,000 it is treated as 1,000
   * @throws NullPointerException if {@code after} is null
   * @throws IllegalArgumentException if {@code limit} is not positive
   * @throws IllegalStateException if content a result refers to is no longer stored
   */
  List<CallResult> results(Seq after, int limit);

  /**
   * Every successful call's result after {@code after}, oldest first, read a page at a time as the
   * stream is consumed. It holds nothing open, so it need not be closed; a short-circuiting
   * operation such as {@code anyMatch} stops the reading.
   *
   * @param after the position to read after; {@link Seq#NONE} reads from the start
   * @throws NullPointerException if {@code after} is null
   * @throws IllegalStateException if content a result refers to is no longer stored, found when the
   *     page holding it is read
   */
  Stream<CallResult> allResults(Seq after);

  /**
   * The facts the call's approver was shown, as they stood when it decided or deferred; an empty
   * object when there were none.
   *
   * <p>A decision's own facts win when it has any. A decision made after a deferral carries none,
   * and a call that expired while waiting carries none, so for those the facts the call was
   * deferred with answer; when a call was asked again, they are the last ones before the decision.
   * While the call is still waiting they are the last deferral's.
   *
   * <p>Empty (the {@code Optional}) when the key is not in this agent's story or nothing has been
   * recorded for the call's approval yet. A call discharged before it was put to its approver, or
   * whose request expired unasked, reads as an empty object. Facts are read back from storage,
   * where a number reads in the narrowest type, so compare them by their text or field by field,
   * not with {@code equals}.
   *
   * <p>Reads the story from its start until the call is decided, a page at a time, so its cost
   * grows with the story.
   *
   * @throws NullPointerException if {@code key} is null
   */
  Optional<JsonNode> approvalFacts(IdempotencyKey key);
}
