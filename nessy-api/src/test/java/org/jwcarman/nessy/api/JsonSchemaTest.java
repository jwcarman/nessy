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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** One type for every schema, and it reads as the document it holds. */
@DisplayName("A JSON Schema")
class JsonSchemaTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /**
   * <b>The bare string, not an object wrapping one.</b> A schema travels inside other things, and a
   * reader of what was written down should see a schema rather than {@code {"json": ...}}. Asserted
   * rather than assumed: {@code @JsonValue} on a record component is the whole mechanism, and
   * nothing else would fail if it were dropped.
   */
  @Test
  void is_written_down_as_the_document_it_holds() {
    String written = MAPPER.writeValueAsString(new JsonSchema("{\"type\":\"object\"}"));

    assertThat(written).isEqualTo("\"{\\\"type\\\":\\\"object\\\"}\"");
  }

  @Test
  void comes_back_from_that_same_string() {
    JsonSchema schema = new JsonSchema("{\"type\":\"object\"}");

    String written = MAPPER.writeValueAsString(schema);

    assertThat(MAPPER.readValue(written, JsonSchema.class)).isEqualTo(schema);
  }

  /** Blank is not a schema, and the producer finds out here rather than at a vendor's API. */
  @Test
  void refuses_nothing_to_say() {
    assertThatThrownBy(() -> new JsonSchema("  ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new JsonSchema(null)).isInstanceOf(NullPointerException.class);
  }
}
