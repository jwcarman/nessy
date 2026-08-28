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
package org.jwcarman.nessy.examples.watchman.pekko;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

@DisplayName("Claims held for the duration of a turn")
class ClaimsTest {

  private Claims claims;

  @BeforeEach
  void setUp() {
    claims = new Claims(new InMemorySubstrate(Clock.systemUTC()));
  }

  @Test
  void what_goes_in_comes_back_out() {
    String id = claims.put("agent-a", "turn-1", "{\"path\":\"/etc/hosts\"}".getBytes(UTF_8));

    assertThat(claims.get("agent-a", "turn-1", id)).isPresent();
    assertThat(new String(claims.get("agent-a", "turn-1", id).orElseThrow(), UTF_8))
        .contains("/etc/hosts");
  }

  @Test
  void a_turns_claims_are_deleted_together() {
    String first = claims.put("agent-a", "turn-1", "one".getBytes(UTF_8));
    String second = claims.put("agent-a", "turn-1", "two".getBytes(UTF_8));
    String other = claims.put("agent-a", "turn-2", "keep me".getBytes(UTF_8));

    claims.deleteTurn("agent-a", "turn-1");

    assertThat(claims.get("agent-a", "turn-1", first)).isEmpty();
    assertThat(claims.get("agent-a", "turn-1", second)).isEmpty();
    assertThat(claims.get("agent-a", "turn-2", other)).isPresent();
  }

  @Test
  void an_orphan_no_state_ever_referenced_is_swept_with_the_rest() {
    // Written, then the process died before the phase referencing it was persisted. Nothing names
    // it -- and it still goes, because the KIND is the owner.
    claims.put("agent-a", "turn-1", "orphan".getBytes(UTF_8));

    claims.deleteTurn("agent-a", "turn-1");

    assertThat(claims.keysOf("agent-a", "turn-1")).isEmpty();
  }

  @Test
  void a_missing_claim_is_absent_rather_than_an_error() {
    assertThat(claims.get("agent-a", "turn-9", "nope")).isEmpty();
  }
}
