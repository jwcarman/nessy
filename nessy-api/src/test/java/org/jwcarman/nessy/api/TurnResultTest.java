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
package org.jwcarman.nessy.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * How a turn ended — a sealed grammar whose wire names are a compatibility surface (narration
 * delivered over SSE names them). Each arm is exercised as a real outcome round-tripping through
 * the discriminator, not merely constructed, since that round trip IS the property that matters: a
 * chat UI on the other end of an SSE stream reads {@code type} to decide how to render the turn.
 */
@DisplayName("How a turn ended")
class TurnResultTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Nested
  class TheFourWaysATurnStops {

    @Test
    @DisplayName("completed carries no data, only the fact that the model finished")
    void completed_round_trips_by_its_own_name() throws Exception {
      TurnResult written = new TurnResult.Completed();

      String json = MAPPER.writeValueAsString(written);
      TurnResult read = MAPPER.readValue(json, TurnResult.class);

      assertThat(json).contains("\"type\":\"completed\"");
      assertThat(read).isEqualTo(written);
    }

    @Test
    @DisplayName("truncated marks an answer as cut off mid-thought, not finished")
    void truncated_round_trips_by_its_own_name() throws Exception {
      TurnResult written = new TurnResult.Truncated();

      String json = MAPPER.writeValueAsString(written);
      TurnResult read = MAPPER.readValue(json, TurnResult.class);

      assertThat(json).contains("\"type\":\"truncated\"");
      assertThat(read).isEqualTo(written);
    }

    @Test
    @DisplayName("refused carries the provider's classification and its explanation, if any")
    void refused_round_trips_with_its_category_and_explanation() throws Exception {
      TurnResult written = new TurnResult.Refused("self-harm", "flagged by the safety classifier");

      String json = MAPPER.writeValueAsString(written);
      TurnResult read = MAPPER.readValue(json, TurnResult.class);

      assertThat(json).contains("\"type\":\"refused\"");
      assertThat(read).isEqualTo(written);
      assertThat(((TurnResult.Refused) read).category()).isEqualTo("self-harm");
      assertThat(((TurnResult.Refused) read).explanation())
          .isEqualTo("flagged by the safety classifier");
    }

    @Test
    @DisplayName("failed carries the reason something broke")
    void failed_round_trips_with_its_reason() throws Exception {
      TurnResult written = new TurnResult.Failed("rate limited");

      String json = MAPPER.writeValueAsString(written);
      TurnResult read = MAPPER.readValue(json, TurnResult.class);

      assertThat(json).contains("\"type\":\"failed\"");
      assertThat(read).isEqualTo(written);
      assertThat(((TurnResult.Failed) read).reason()).isEqualTo("rate limited");
    }
  }

  @Nested
  class Refusing {

    @Test
    void a_refusal_with_no_category_is_refused() {
      assertThatThrownBy(() -> new TurnResult.Refused(null, "explained"))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("category");
    }

    @Test
    void a_refusal_with_no_explanation_is_refused() {
      assertThatThrownBy(() -> new TurnResult.Refused("self-harm", null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("explanation");
    }

    @Test
    void a_failure_with_no_reason_is_refused() {
      assertThatThrownBy(() -> new TurnResult.Failed(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("reason");
    }
  }
}
