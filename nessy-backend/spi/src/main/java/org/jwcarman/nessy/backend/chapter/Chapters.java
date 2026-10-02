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
package org.jwcarman.nessy.backend.chapter;

import java.util.List;
import java.util.Optional;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.turn.Chapter;
import org.jwcarman.nessy.api.turn.Summary;

/**
 * The closed chapters of every agent's history, and the text that stands in for each.
 *
 * <p>A chapter is a run of whole turns. An agent's chapters are contiguous in the order they were
 * closed and never overlap, and this store is what keeps them that way: {@link #append} is
 * conditional on where the closed chapters currently end, so two callers that cut the same turns
 * differently cannot both succeed, and neither can leave a gap or an overlap behind. The caller
 * that loses is told so and re-reads {@link #closedThrough}.
 *
 * <p>A chapter's bounds never change once stored. Its text is written separately and later, once:
 * closing a chapter is quick and decided from the turns alone, while writing what stands in for it
 * can be slow and can fail, and the two must not hold each other up.
 */
public interface Chapters {

  /**
   * Appends chapters to the end of an agent's closed chapters, all of them or none.
   *
   * @param after where the caller believes the agent's closed chapters end; empty for none
   * @return true if they were stored; false if the closed chapters no longer end at {@code after}
   * @throws IllegalArgumentException if a chapter is not for this agent, if the chapters are not in
   *     ascending order without overlap, or if the first does not come after {@code after}
   */
  boolean append(AgentType type, AgentId agent, Optional<TurnId> after, List<Chapter> chapters);

  /** Stores the text for a chapter that has none. False if it already has one or does not exist. */
  boolean summarize(Summary summary);

  /** The last turn of the agent's last closed chapter. */
  Optional<TurnId> closedThrough(AgentType type, AgentId agent);

  /** Closed chapters with no summary yet, oldest first. */
  List<Chapter> unsummarized(AgentType type, AgentId agent);

  /**
   * The unbroken run of summarised chapters from the agent's first chapter, oldest first. A chapter
   * with no summary ends the run, even if a later one has been written.
   */
  List<Summary> summaries(AgentType type, AgentId agent);
}
