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

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

@DisplayName("A turn's scratch space")
class ClaimsTest {

  private static final AgentId HOUSE = AgentId.of("house-12");

  private InMemorySubstrate substrate;
  private Claims claims;

  @BeforeEach
  void setUp() {
    substrate = new InMemorySubstrate(Clock.systemUTC());
    claims = new Claims(substrate);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static String text(byte[] value) {
    return new String(value, StandardCharsets.UTF_8);
  }

  @Test
  void what_goes_in_comes_back() {
    claims.put(HOUSE, "turn-1", "asked", bytes("the question"));

    assertThat(claims.get(HOUSE, "turn-1", "asked"))
        .isPresent()
        .get()
        .extracting(ClaimsTest::text)
        .isEqualTo("the question");
  }

  @Test
  @DisplayName("claiming the same key twice overwrites, because a re-driven turn does exactly that")
  void a_claim_can_be_written_again() {
    claims.put(HOUSE, "turn-1", "asked", bytes("first attempt"));

    claims.put(HOUSE, "turn-1", "asked", bytes("after a crash"));

    assertThat(claims.get(HOUSE, "turn-1", "asked"))
        .isPresent()
        .get()
        .extracting(ClaimsTest::text)
        .isEqualTo("after a crash");
  }

  @Test
  void a_batch_lands_whole() {
    claims.putAll(HOUSE, "turn-1", Map.of("a", bytes("one"), "b", bytes("two")));

    assertThat(claims.get(HOUSE, "turn-1", "a")).isPresent();
    assertThat(claims.get(HOUSE, "turn-1", "b")).isPresent();
  }

  @Test
  void one_turn_cannot_see_another_turn_s_claims() {
    claims.put(HOUSE, "turn-1", "asked", bytes("mine"));

    assertThat(claims.get(HOUSE, "turn-2", "asked")).isEmpty();
  }

  @Test
  @DisplayName("ending a turn sweeps everything under it, including what nothing referenced")
  void deleting_a_turn_takes_orphans_too() {
    claims.put(HOUSE, "turn-1", "asked", bytes("the question"));
    claims.put(HOUSE, "turn-1", "result-c1", bytes("an answer"));
    // Written just before a notional crash: in the kind, named by no state anywhere.
    claims.put(HOUSE, "turn-1", "orphan", bytes("nobody remembers me"));

    claims.deleteTurn(HOUSE, "turn-1");

    assertThat(claims.get(HOUSE, "turn-1", "asked")).isEmpty();
    assertThat(claims.get(HOUSE, "turn-1", "result-c1")).isEmpty();
    assertThat(claims.get(HOUSE, "turn-1", "orphan")).isEmpty();
    assertThat(substrate.keys(Claims.kindOf(HOUSE, "turn-1"), 100)).isEmpty();
  }

  @Test
  void ending_a_turn_leaves_other_turns_alone() {
    claims.put(HOUSE, "turn-1", "asked", bytes("mine"));
    claims.put(HOUSE, "turn-2", "asked", bytes("theirs"));

    claims.deleteTurn(HOUSE, "turn-1");

    assertThat(claims.get(HOUSE, "turn-2", "asked")).isPresent();
  }

  @Test
  void ending_a_turn_that_claimed_nothing_is_not_an_error() {
    claims.deleteTurn(HOUSE, "turn-never-ran");

    assertThat(claims.get(HOUSE, "turn-never-ran", "anything")).isEmpty();
  }
}
