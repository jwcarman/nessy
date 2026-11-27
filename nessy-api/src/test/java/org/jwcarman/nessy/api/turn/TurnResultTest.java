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
package org.jwcarman.nessy.api.turn;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class TurnResultTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Test
  void a_failed_turn_keeps_its_reason_when_written_down_and_read_back() {
    TurnResult failed = new TurnResult.Failed("the vendor was unreachable");

    TurnResult read = MAPPER.readValue(MAPPER.writeValueAsString(failed), TurnResult.class);

    assertThat(read).isEqualTo(failed);
  }

  @Test
  void a_failed_turn_written_down_before_reasons_were_recorded_still_reads_back() {
    TurnResult read = MAPPER.readValue("{\"type\":\"failed\"}", TurnResult.class);

    assertThat(read).isEqualTo(new TurnResult.Failed(TurnResult.Failed.UNRECORDED));
  }

  @Test
  void a_failed_turn_with_no_reason_says_so_rather_than_saying_nothing() {
    assertThat(new TurnResult.Failed(null).reason()).isEqualTo(TurnResult.Failed.UNRECORDED);
    assertThat(new TurnResult.Failed("  ").reason()).isEqualTo(TurnResult.Failed.UNRECORDED);
  }
}
