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

import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.spi.intent.IntentStore;
import org.jwcarman.nessy.spi.intent.IntentStore.StoredIntent;

/**
 * The technology-compatibility kit every {@link IntentStore} implementation must pass: put/get
 * round-trip, upsert replacement (last write wins), clear and its idempotence, absence, and
 * conversation isolation — pinned as law rather than left to each implementation's own judgment.
 *
 * <p>Test methods are {@code public} — a nested-subscriber discovery lesson learned elsewhere in
 * this kit: a package-private {@code @Test} method inherited into a {@code @Nested} class is not
 * always picked up the same way by every JUnit runner, so this contract states its methods public
 * rather than risk it.
 */
public abstract class IntentStoreContract {

  /** The store under test — fresh and empty for each test. */
  protected abstract IntentStore intents();

  @Test
  public void a_put_intent_is_found_by_its_conversation_id() {
    ConversationId id = ConversationId.generate();
    StoredIntent intent = new StoredIntent("com.example.BookFlight", "{\"destination\":\"DEN\"}");

    intents().put(id, intent.type(), intent.json());

    assertThat(intents().get(id)).contains(intent);
  }

  @Test
  public void putting_again_replaces_the_intent_last_write_wins() {
    ConversationId id = ConversationId.generate();
    intents().put(id, "com.example.BookFlight", "{\"destination\":\"DEN\"}");

    StoredIntent replacement =
        new StoredIntent("com.example.CancelFlight", "{\"reason\":\"changed plans\"}");
    intents().put(id, replacement.type(), replacement.json());

    assertThat(intents().get(id)).contains(replacement);
  }

  @Test
  public void putting_never_creates_a_second_row_for_the_same_conversation() {
    ConversationId id = ConversationId.generate();
    intents().put(id, "com.example.BookFlight", "{\"destination\":\"DEN\"}");
    intents().put(id, "com.example.BookFlight", "{\"destination\":\"ORD\"}");
    intents().put(id, "com.example.CancelFlight", "{\"reason\":\"changed plans\"}");

    assertThat(intents().get(id))
        .contains(new StoredIntent("com.example.CancelFlight", "{\"reason\":\"changed plans\"}"));
  }

  @Test
  public void clear_removes_the_intent() {
    ConversationId id = ConversationId.generate();
    intents().put(id, "com.example.BookFlight", "{\"destination\":\"DEN\"}");

    intents().clear(id);

    assertThat(intents().get(id)).isEmpty();
  }

  @Test
  public void clearing_an_absent_conversation_is_a_no_op() {
    ConversationId id = ConversationId.generate();

    intents().clear(id);

    assertThat(intents().get(id)).isEmpty();
  }

  @Test
  public void a_conversation_that_never_declared_an_intent_finds_nothing() {
    assertThat(intents().get(ConversationId.generate())).isEmpty();
  }

  @Test
  public void two_conversations_never_see_each_others_intent() {
    ConversationId mine = ConversationId.generate();
    ConversationId theirs = ConversationId.generate();
    StoredIntent myIntent = new StoredIntent("com.example.BookFlight", "{\"destination\":\"DEN\"}");
    intents().put(mine, myIntent.type(), myIntent.json());
    intents().put(theirs, "com.example.CancelFlight", "{\"reason\":\"changed plans\"}");

    assertThat(intents().get(mine)).contains(myIntent);

    // Clearing mine must not touch theirs — closes the hole a conversation-blind clear would
    // leave open.
    intents().clear(mine);

    assertThat(intents().get(mine)).isEmpty();
    assertThat(intents().get(theirs))
        .contains(new StoredIntent("com.example.CancelFlight", "{\"reason\":\"changed plans\"}"));
  }
}
