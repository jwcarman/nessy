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
package org.jwcarman.nessy.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.tool.ReplyToken;

/**
 * The token a deferring tool hands to a stranger.
 *
 * <p>Two properties the engine owes it: a holder can neither READ the coordinates nor FORGE a token
 * for a call it was never given.
 */
@DisplayName("A reply token")
class ReplyTokensTest {

  private static final AgentType WATCHMAN = AgentType.of("watchman");
  private static final AgentId HOUSE = AgentId.of("house-12");

  private final ReplyTokens tokens = ReplyTokens.ephemeral();

  @Test
  void names_the_call_it_was_minted_for() {
    ReplyToken token = tokens.mint(WATCHMAN, HOUSE, "c1");

    ReplyTokens.Coordinates where = tokens.read(token);

    assertThat(where.agentType()).isEqualTo("watchman");
    assertThat(where.agentId()).isEqualTo("house-12");
    assertThat(where.callId()).isEqualTo("c1");
  }

  /**
   * A LONG call id, not the {@code "c1"} the tests beside this one use.
   *
   * <p>This is the only test that asserts a string is ABSENT from the token, and a token is random
   * base64url — so a two-character needle turns up inside one by pure chance every few runs, and
   * the test fails having found nothing wrong. The needle has to be long enough that a collision is
   * not a thing that happens.
   */
  private static final String LONG_CALL_ID = "call-9f3a2d7c41b8";

  @Test
  @DisplayName("a holder cannot read what is inside it")
  void the_coordinates_do_not_appear_in_the_token() {
    ReplyToken token = tokens.mint(WATCHMAN, HOUSE, LONG_CALL_ID);

    assertThat(token.value()).doesNotContain("watchman", "house-12", LONG_CALL_ID);
  }

  @Test
  @DisplayName("editing one changes nothing except that it stops working")
  void a_tampered_token_is_refused() {
    ReplyToken token = tokens.mint(WATCHMAN, HOUSE, "c1");
    char[] edited = token.value().toCharArray();
    edited[edited.length - 1] = edited[edited.length - 1] == 'A' ? 'B' : 'A';
    ReplyToken forged = ReplyToken.of(new String(edited));

    assertThatThrownBy(() -> tokens.read(forged)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_token_from_another_engine_is_refused() {
    ReplyToken theirs = ReplyTokens.ephemeral().mint(WATCHMAN, HOUSE, "c1");

    assertThatThrownBy(() -> tokens.read(theirs)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nonsense_is_refused_rather_than_misread() {
    ReplyToken nonsense = ReplyToken.of("not-a-token");

    assertThatThrownBy(() -> tokens.read(nonsense)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("two tokens for the same call differ: a nonce is never reused")
  void minting_twice_does_not_produce_the_same_token() {
    ReplyToken first = tokens.mint(WATCHMAN, HOUSE, "c1");
    ReplyToken second = tokens.mint(WATCHMAN, HOUSE, "c1");

    assertThat(first).isNotEqualTo(second);
    assertThat(tokens.read(first)).isEqualTo(tokens.read(second));
  }

  private static byte[] key(int seed) {
    byte[] key = new byte[32];
    for (int i = 0; i < key.length; i++) {
      key[i] = (byte) (i + seed);
    }
    return key;
  }

  @Test
  @DisplayName("a token minted before a rotation is still understood after it")
  void an_older_key_still_reads_the_tokens_it_minted() {
    ReplyToken beforeRotation = ReplyTokens.withKey(key(1)).mint(WATCHMAN, HOUSE, "c1");

    ReplyTokens afterRotation = ReplyTokens.withKeys(key(2), key(1));

    assertThat(afterRotation.read(beforeRotation).callId()).isEqualTo("c1");
  }

  @Test
  @DisplayName("dropping the outgoing key is what actually breaks outstanding tokens")
  void a_retired_key_stops_reading_its_own_tokens() {
    ReplyToken beforeRotation = ReplyTokens.withKey(key(1)).mint(WATCHMAN, HOUSE, "c1");

    ReplyTokens rotatedTooSoon = ReplyTokens.withKey(key(2));

    assertThatThrownBy(() -> rotatedTooSoon.read(beforeRotation))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void new_tokens_are_minted_with_the_newest_key() {
    ReplyTokens afterRotation = ReplyTokens.withKeys(key(2), key(1));
    ReplyToken minted = afterRotation.mint(WATCHMAN, HOUSE, "c2");

    assertThat(ReplyTokens.withKey(key(2)).read(minted).callId()).isEqualTo("c2");
  }

  @Test
  void a_supplied_key_survives_a_new_instance_the_way_a_restart_would() {
    byte[] key = new byte[32];
    for (int i = 0; i < key.length; i++) {
      key[i] = (byte) i;
    }
    ReplyToken minted = ReplyTokens.withKey(key).mint(WATCHMAN, HOUSE, "c1");

    assertThat(ReplyTokens.withKey(key).read(minted).callId()).isEqualTo("c1");
  }
}
