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
package org.jwcarman.nessy.store.tck;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.spi.memory.SummaryStore;
import org.jwcarman.nessy.spi.memory.SummaryStore.Summary;

/**
 * The technology-compatibility kit every {@link SummaryStore} implementation must pass: absence,
 * save-then-find, last-write-wins overwriting, and per-conversation isolation — pinned as law
 * rather than left to each implementation's own judgment.
 */
public abstract class SummaryStoreContract {

  /** The store under test — fresh and empty for each test. */
  protected abstract SummaryStore summaries();

  @Test
  void a_conversation_never_summarized_has_no_summary() {
    assertThat(summaries().find(ConversationId.generate())).isEmpty();
  }

  @Test
  void a_saved_summary_is_found_by_its_conversation_id() {
    ConversationId id = ConversationId.generate();
    Summary summary = new Summary(3L, "the story so far");

    summaries().save(id, summary);

    assertThat(summaries().find(id)).contains(summary);
  }

  @Test
  void saving_again_replaces_the_prior_summary_last_write_wins() {
    ConversationId id = ConversationId.generate();
    summaries().save(id, new Summary(3L, "the story so far"));

    Summary replacement = new Summary(7L, "the story, further along");
    summaries().save(id, replacement);

    assertThat(summaries().find(id)).contains(replacement);
  }

  @Test
  void two_conversations_never_see_each_others_summary() {
    ConversationId mine = ConversationId.generate();
    ConversationId theirs = ConversationId.generate();
    Summary mySummary = new Summary(1L, "mine");
    summaries().save(mine, mySummary);
    summaries().save(theirs, new Summary(2L, "theirs"));

    assertThat(summaries().find(mine)).contains(mySummary);
  }
}
