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
package org.jwcarman.nessy.api.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.TurnId;

/**
 * One call the model asked for. Both constructors, the memoizing mint, and the hand-written
 * equality/toString (written because a generated record would compare the reply-token supplier by
 * identity and would print the token itself) are pinned here — nothing in this module ever built
 * one, hence 0% line coverage on a type used constantly downstream.
 */
@DisplayName("A call the model asked for")
class ToolCallRequestTest {

  private static final AgentType WATCHMAN = AgentType.of("watchman");
  private static final AgentId HOUSE = AgentId.of("house-12");
  private static final TurnId TURN = TurnId.of("turn-1");
  private static final CallId CALL = CallId.of("c1");

  private static ToolCallRequest<String> requestMinting(Supplier<ReplyToken> mint) {
    return new ToolCallRequest<>(WATCHMAN, HOUSE, TURN, CALL, "read_file", "/etc/hosts", mint);
  }

  @Nested
  class MintingTheReplyAddress {

    @Test
    @DisplayName("a call built from a supplier mints its token lazily")
    void the_supplier_is_not_consulted_until_asked() {
      AtomicInteger calls = new AtomicInteger();
      ToolCallRequest<String> request =
          requestMinting(
              () -> {
                calls.incrementAndGet();
                return ReplyToken.of("minted");
              });

      assertThat(calls).hasValue(0);

      request.replyToken();

      assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("asking twice mints once and remembers, so two holders get ONE address")
    void the_supplier_is_memoized() {
      AtomicInteger calls = new AtomicInteger();
      ToolCallRequest<String> request =
          requestMinting(
              () -> {
                calls.incrementAndGet();
                return ReplyToken.of("minted");
              });

      ReplyToken first = request.replyToken();
      ReplyToken second = request.replyToken();

      assertThat(first).isEqualTo(second);
      assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName(
        "a call built from a plain token, the convenience constructor, hands back that same token")
    void the_convenience_constructor_wraps_an_already_minted_token() {
      ToolCallRequest<String> request =
          new ToolCallRequest<>(
              WATCHMAN, HOUSE, TURN, CALL, "read_file", "/etc/hosts", ReplyToken.of("t1"));

      assertThat(request.replyToken()).isEqualTo(ReplyToken.of("t1"));
    }
  }

  @Nested
  class Equality {

    @Test
    @DisplayName(
        "two requests naming the same call are equal even though their token suppliers differ")
    void equality_ignores_how_the_address_would_be_minted() {
      ToolCallRequest<String> first = requestMinting(() -> ReplyToken.of("a"));
      ToolCallRequest<String> second = requestMinting(() -> ReplyToken.of("b"));

      assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
    }

    @Test
    @DisplayName("a different call id is a different request")
    void a_different_call_id_is_not_equal() {
      ToolCallRequest<String> first = requestMinting(() -> ReplyToken.of("a"));
      ToolCallRequest<String> second =
          new ToolCallRequest<>(
              WATCHMAN,
              HOUSE,
              TURN,
              CallId.of("other"),
              "read_file",
              "/etc/hosts",
              () -> ReplyToken.of("a"));

      assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("a request is not equal to some unrelated type")
    void a_request_is_not_equal_to_something_else() {
      ToolCallRequest<String> request = requestMinting(() -> ReplyToken.of("a"));

      assertThat(request).isNotEqualTo("not a request");
    }
  }

  @Nested
  class Printing {

    @Test
    @DisplayName(
        "toString does NOT contain the reply token — it is a credential, and this may reach a log")
    void the_reply_token_is_never_printed() {
      ToolCallRequest<String> request = requestMinting(() -> ReplyToken.of("super-secret-token"));

      // Force the token to be minted, so a leak via toString would have something to leak.
      request.replyToken();

      assertThat(request.toString()).doesNotContain("super-secret-token");
    }

    @Test
    @DisplayName("toString names the call so a log line is legible")
    void the_visible_fields_are_present() {
      ToolCallRequest<String> request = requestMinting(() -> ReplyToken.of("t1"));

      assertThat(request.toString())
          .contains("watchman")
          .contains("house-12")
          .contains("turn-1")
          .contains("c1")
          .contains("read_file")
          .contains("/etc/hosts");
    }
  }

  @Nested
  class TheIdempotencyKey {

    @Test
    @DisplayName("the call key is the turn and the call, since a call id repeats across turns")
    void call_key_composes_turn_and_call() {
      ToolCallRequest<String> request = requestMinting(() -> ReplyToken.of("t1"));

      assertThat(request.callKey()).isEqualTo("turn-1/c1");
    }
  }
}
