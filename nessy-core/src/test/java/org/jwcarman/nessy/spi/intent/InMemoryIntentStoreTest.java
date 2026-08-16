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
package org.jwcarman.nessy.spi.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.conversation.ConversationId;

/** {@link InMemoryIntentStore}, reached through {@link IntentStore#inMemory()}. */
class InMemoryIntentStoreTest {

  private final IntentStore store = IntentStore.inMemory();

  @Nested
  class Getting {

    @Test
    void a_conversation_never_put_to_is_absent() {
      assertThat(store.get(ConversationId.generate())).isEmpty();
    }

    @Test
    void a_put_intent_is_found_by_the_same_conversation() {
      ConversationId id = ConversationId.generate();

      store.put(id, "com.example.BookFlight", "{\"destination\":\"DEN\"}");

      assertThat(store.get(id))
          .contains(
              new IntentStore.StoredIntent("com.example.BookFlight", "{\"destination\":\"DEN\"}"));
    }
  }

  @Nested
  class Putting {

    @Test
    void putting_again_replaces_the_intent_last_write_wins() {
      ConversationId id = ConversationId.generate();
      store.put(id, "com.example.BookFlight", "{\"destination\":\"DEN\"}");

      store.put(id, "com.example.CancelFlight", "{\"reason\":\"changed plans\"}");

      assertThat(store.get(id))
          .contains(
              new IntentStore.StoredIntent(
                  "com.example.CancelFlight", "{\"reason\":\"changed plans\"}"));
    }

    @Test
    void a_null_id_is_rejected() {
      assertThatThrownBy(() -> store.put(null, "com.example.BookFlight", "{}"))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("id must not be null");
    }

    @Test
    void a_null_type_is_rejected() {
      ConversationId id = ConversationId.generate();

      assertThatThrownBy(() -> store.put(id, null, "{}"))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("type must not be null");
    }

    @Test
    void a_null_json_is_rejected() {
      ConversationId id = ConversationId.generate();

      assertThatThrownBy(() -> store.put(id, "com.example.BookFlight", null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("json must not be null");
    }
  }

  @Nested
  class Clearing {

    @Test
    void clearing_a_put_intent_removes_it() {
      ConversationId id = ConversationId.generate();
      store.put(id, "com.example.BookFlight", "{\"destination\":\"DEN\"}");

      store.clear(id);

      assertThat(store.get(id)).isEmpty();
    }

    @Test
    void clearing_an_absent_conversation_is_a_no_op() {
      ConversationId id = ConversationId.generate();

      store.clear(id);

      assertThat(store.get(id)).isEmpty();
    }
  }

  @Nested
  class Conversation_isolation {

    @Test
    void two_conversations_never_see_each_others_intent() {
      ConversationId mine = ConversationId.generate();
      ConversationId theirs = ConversationId.generate();
      store.put(mine, "com.example.BookFlight", "{\"destination\":\"DEN\"}");

      store.put(theirs, "com.example.CancelFlight", "{\"reason\":\"changed plans\"}");

      assertThat(store.get(mine))
          .contains(
              new IntentStore.StoredIntent("com.example.BookFlight", "{\"destination\":\"DEN\"}"));
      store.clear(mine);
      assertThat(store.get(theirs))
          .contains(
              new IntentStore.StoredIntent(
                  "com.example.CancelFlight", "{\"reason\":\"changed plans\"}"));
    }
  }

  @Nested
  class Concurrency {

    @Test
    void concurrent_puts_on_one_conversation_do_not_throw() throws InterruptedException {
      ConversationId id = ConversationId.generate();
      int writers = 16;
      Thread[] threads = new Thread[writers];
      for (int i = 0; i < writers; i++) {
        int index = i;
        threads[i] =
            new Thread(
                () -> store.put(id, "com.example.BookFlight", "{\"attempt\":" + index + "}"));
      }
      for (Thread thread : threads) {
        thread.start();
      }
      for (Thread thread : threads) {
        thread.join();
      }

      assertThat(store.get(id)).isPresent();
    }
  }
}
