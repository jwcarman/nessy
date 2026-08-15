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
package org.jwcarman.nessy.tck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.conversation.InboxEntry;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.jwcarman.nessy.spi.conversation.StaleStateException;

/**
 * The technology-compatibility kit every {@link ConversationStore} implementation must pass: the
 * fenced save and the inbox, pinned as law rather than left to each implementation's own judgment.
 * The registry's own contract ({@code Parks.park}/{@code find}/{@code forConversation}) lives at
 * {@code ParksContract}, a separate door with a separate story (design §5).
 *
 * <p>{@link #store()} returns the same instance for the whole of one test, and a fresh, empty
 * instance for the next: {@link #newStore()} is the factory each concrete subclass supplies, and
 * {@link #nessy_store_contract_prepares_a_fresh_store()} calls it once per test, before the test
 * body runs, stashing the result in a field that {@link #store()} hands back for the rest of that
 * test.
 */
public abstract class ConversationStoreContract {

  /** A fresh, empty store — called once per test, never reused across tests. */
  protected abstract ConversationStore newStore();

  private ConversationStore store;

  @BeforeEach
  void nessy_store_contract_prepares_a_fresh_store() {
    store = newStore();
  }

  /** The store under test, pinned for the duration of the current test. */
  protected ConversationStore store() {
    return store;
  }

  @Test
  void load_of_an_unknown_conversation_is_empty() {
    assertThat(store().load(ConversationId.generate())).isEmpty();
  }

  @Test
  void save_persists_and_bumps_the_version() {
    ConversationId id = ConversationId.generate();

    ConversationState saved = store().save(ConversationState.newConversation(id), List.of());

    assertThat(saved.version()).isEqualTo(1L);
    assertThat(store().load(id)).isPresent();
    assertThat(store().load(id).orElseThrow().state().version()).isEqualTo(1L);
  }

  @Test
  void a_stale_save_fails_loudly_naming_both_versions() {
    ConversationId id = ConversationId.generate();
    store().save(ConversationState.newConversation(id), List.of());
    ConversationState firstReader = store().load(id).orElseThrow().state();
    ConversationState secondReader = store().load(id).orElseThrow().state();
    store().save(firstReader, List.of());
    ConversationStore underTest = store();

    assertThatThrownBy(() -> underTest.save(secondReader, List.of()))
        .isInstanceOf(StaleStateException.class)
        .hasMessageContaining("1")
        .hasMessageContaining("2");
  }

  @Test
  void an_append_before_any_save_still_loads_as_a_fresh_conversation() {
    ConversationId id = ConversationId.generate();
    InboxEntry told = InboxEntry.told(List.of(new TextBlock("hi")));

    store().append(id, told);

    ConversationStore.Loaded loaded = store().load(id).orElseThrow();
    assertThat(loaded.state()).isEqualTo(ConversationState.newConversation(id));
    assertThat(loaded.inbox()).containsExactly(told);
  }

  @Test
  void appends_are_unconditional_and_ordered() {
    ConversationId id = ConversationId.generate();
    store().save(ConversationState.newConversation(id), List.of());
    InboxEntry first = InboxEntry.told(List.of(new TextBlock("first")));
    InboxEntry second = InboxEntry.told(List.of(new TextBlock("second")));
    InboxEntry third = InboxEntry.told(List.of(new TextBlock("third")));

    store().append(id, first);
    store().append(id, second);
    store().append(id, third);

    assertThat(store().load(id).orElseThrow().inbox()).containsExactly(first, second, third);
  }

  @Test
  void an_append_never_disturbs_a_pending_save() {
    ConversationId id = ConversationId.generate();
    store().save(ConversationState.newConversation(id), List.of());
    ConversationState loaded = store().load(id).orElseThrow().state();

    store().append(id, InboxEntry.told(List.of(new TextBlock("hi"))));
    ConversationState saved = store().save(loaded, List.of());

    assertThat(saved.version()).isEqualTo(loaded.version() + 1);
  }

  @Test
  void draining_removes_exactly_the_named_entries_atomically_with_the_save() {
    ConversationId id = ConversationId.generate();
    ConversationState v1 = store().save(ConversationState.newConversation(id), List.of());
    InboxEntry.Told keep = InboxEntry.told(List.of(new TextBlock("keep")));
    InboxEntry.Told drain = InboxEntry.told(List.of(new TextBlock("drain")));
    store().append(id, keep);
    store().append(id, drain);

    store().save(v1, List.of(drain.id()));

    assertThat(store().load(id).orElseThrow().inbox()).containsExactly(keep);
  }

  @Test
  void a_load_after_a_draining_save_never_pairs_the_bumped_version_with_the_drained_entry() {
    ConversationId id = ConversationId.generate();
    ConversationState v1 = store().save(ConversationState.newConversation(id), List.of());
    InboxEntry.Told keep = InboxEntry.told(List.of(new TextBlock("keep")));
    InboxEntry.Told drain = InboxEntry.told(List.of(new TextBlock("drain")));
    store().append(id, keep);
    store().append(id, drain);

    ConversationState v2 = store().save(v1, List.of(drain.id()));

    ConversationStore.Loaded loaded = store().load(id).orElseThrow();
    assertThat(loaded.state().version()).isEqualTo(v2.version());
    assertThat(loaded.inbox()).containsExactly(keep);
  }
}
