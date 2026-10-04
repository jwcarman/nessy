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
package org.jwcarman.nessy.backend.payload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("What a stored document is")
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PayloadsContentTest {

  @Test
  void a_document_holds_what_it_was_given() {
    JsonNode json = JsonMapper.builder().build().readTree("{\"a\":1}");

    assertThat(new Payloads.Document(json).document()).isEqualTo(json);
  }

  @Test
  void a_document_refuses_null() {
    assertThatThrownBy(() -> new Payloads.Document(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("document must not be null");
  }
}
