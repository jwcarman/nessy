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

import org.jwcarman.nessy.api.turn.Chapter;

/**
 * Writes what stands in for one closed chapter.
 *
 * <p>Handed the chapter and nothing else, so a summarizer that reads the chapter's turns, or the
 * summaries of earlier chapters, loads them itself.
 *
 * <p>Called when a turn ends, off the agent's thread, so it may block and it may call a model. It
 * returns text; blank text is treated as having failed.
 */
@FunctionalInterface
public interface Summarizer {

  /** The text that stands in for {@code chapter}'s turns. */
  String summarize(Chapter chapter);
}
