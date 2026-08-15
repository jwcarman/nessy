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
package org.jwcarman.nessy.spi.memory;

import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.conversation.ConversationId;

/**
 * One conversation's folded prefix: the summary text a {@link SummarizingHydrator} has already
 * distilled from the transcript, and the transcript version through which it speaks for the
 * conversation.
 *
 * <p>There is no fencing here (design §10): {@link #save} is last-write-wins, on purpose. A lost or
 * clobbered write is never a lost word — the transcript is the truth a summary is only ever a
 * cheaper way to re-read, so a crash between summarizing and saving simply means the same tail gets
 * re-summarized on the next recall, landing on the same watermark. Cheap re-work, never data loss.
 */
public interface SummaryStore {

  /**
   * @param watermark the transcript version this summary already accounts for — everything at or
   *     below it is folded into {@code text}, nothing above it is
   * @param text the folded prefix itself; empty for a conversation nothing has been summarized for
   *     yet
   */
  record Summary(long watermark, String text) {

    public Summary {
      Objects.requireNonNull(text, "text must not be null");
    }
  }

  /** The current summary for {@code id}, or empty if nothing has been summarized yet. */
  Optional<Summary> find(ConversationId id);

  /** Replaces whatever summary {@code id} had, last write wins, no fencing (see the class doc). */
  void save(ConversationId id, Summary summary);

  /** The zero-configuration default: summaries live in this JVM and die with it. */
  static SummaryStore inMemory() {
    return new InMemorySummaryStore();
  }
}
