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
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.spi.memory.Transcript;
import org.jwcarman.nessy.spi.memory.Transcript.Entry;

/**
 * The technology-compatibility kit every {@link Transcript} implementation must pass: monotonic
 * versions, the no-stutter append rule, and the three read shapes, pinned as law rather than left
 * to each implementation's own judgment.
 */
public abstract class TranscriptContract {

  /** The transcript under test — fresh and empty for each test. */
  protected abstract Transcript transcript();

  @Test
  void append_assigns_versions_starting_at_zero_and_climbing_by_one() {
    ConversationId id = ConversationId.generate();

    Entry first = transcript().append(id, Message.user("one"));
    Entry second = transcript().append(id, Message.user("two"));
    Entry third = transcript().append(id, Message.user("three"));

    assertThat(first.version()).isZero();
    assertThat(second.version()).isEqualTo(1L);
    assertThat(third.version()).isEqualTo(2L);
  }

  @Test
  void all_returns_every_entry_in_version_order() {
    ConversationId id = ConversationId.generate();
    Message one = Message.user("one");
    Message two = Message.user("two");
    Message three = Message.user("three");

    transcript().append(id, one);
    transcript().append(id, two);
    transcript().append(id, three);

    assertThat(transcript().all(id)).extracting(Entry::message).containsExactly(one, two, three);
  }

  @Test
  void appending_a_message_equal_to_the_current_last_entry_returns_that_entry_unchanged() {
    ConversationId id = ConversationId.generate();
    Entry firstAppend = transcript().append(id, Message.user("hello"));

    // A separately-constructed but value-equal message — the shape a crash-recovery
    // replay actually re-delivers, never the same object reference.
    Entry secondAppend = transcript().append(id, Message.user("hello"));

    assertThat(secondAppend).isEqualTo(firstAppend);
    assertThat(transcript().all(id)).hasSize(1);
  }

  @Test
  void appending_a_message_equal_to_an_earlier_but_not_the_last_entry_still_appends() {
    ConversationId id = ConversationId.generate();
    transcript().append(id, Message.user("first"));
    transcript().append(id, Message.user("second"));

    // Value-equal to the first append, not to the last — the no-stutter rule must not fire.
    Entry third = transcript().append(id, Message.user("first"));

    assertThat(third.message()).isEqualTo(Message.user("first"));
    assertThat(third.version()).isEqualTo(2L);
    assertThat(transcript().all(id)).hasSize(3);
  }

  @Test
  void tail_after_the_head_version_is_empty() {
    ConversationId id = ConversationId.generate();
    transcript().append(id, Message.user("only"));

    assertThat(transcript().tail(id, 0L)).isEmpty();
  }

  @Test
  void tail_returns_only_entries_with_version_strictly_greater_than_the_bound() {
    ConversationId id = ConversationId.generate();
    Message one = Message.user("one");
    Message two = Message.user("two");
    Message three = Message.user("three");
    transcript().append(id, one);
    transcript().append(id, two);
    transcript().append(id, three);

    assertThat(transcript().tail(id, 0L)).extracting(Entry::message).containsExactly(two, three);
  }

  @Test
  void page_returns_the_newest_limit_entries_strictly_below_the_bound_in_version_order() {
    ConversationId id = ConversationId.generate();
    Message one = Message.user("one");
    Message two = Message.user("two");
    Message three = Message.user("three");
    Message four = Message.user("four");
    transcript().append(id, one);
    transcript().append(id, two);
    transcript().append(id, three);
    transcript().append(id, four);

    assertThat(transcript().page(id, 3L, 2)).extracting(Entry::message).containsExactly(two, three);
  }

  @Test
  void page_returns_the_full_remainder_when_fewer_entries_exist_than_the_limit() {
    ConversationId id = ConversationId.generate();
    Message one = Message.user("one");
    Message two = Message.user("two");
    transcript().append(id, one);
    transcript().append(id, two);

    assertThat(transcript().page(id, 2L, 10)).extracting(Entry::message).containsExactly(one, two);
  }

  @Test
  void an_unknown_conversation_returns_empty_lists_from_every_read() {
    ConversationId id = ConversationId.generate();

    assertThat(transcript().all(id)).isEmpty();
    assertThat(transcript().tail(id, 0L)).isEmpty();
    assertThat(transcript().page(id, 0L, 10)).isEmpty();
  }

  @Test
  void two_conversations_never_see_each_others_entries() {
    ConversationId first = ConversationId.generate();
    ConversationId second = ConversationId.generate();
    Message toFirst = Message.user("for the first conversation");

    transcript().append(first, toFirst);

    assertThat(transcript().all(first)).extracting(Entry::message).containsExactly(toFirst);
    assertThat(transcript().all(second)).isEmpty();
  }
}
