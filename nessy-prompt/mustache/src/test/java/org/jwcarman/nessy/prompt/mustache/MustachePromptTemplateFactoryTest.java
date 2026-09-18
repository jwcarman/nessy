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
package org.jwcarman.nessy.prompt.mustache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samskivert.mustache.MustacheException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.prompt.PromptTemplate;
import org.jwcarman.nessy.prompt.PromptVariables;

@DisplayName("The Mustache template engine")
class MustachePromptTemplateFactoryTest {

  private final MustachePromptTemplateFactory engine = new MustachePromptTemplateFactory();

  @Test
  void fills_placeholders_by_name() {
    PromptTemplate template = engine.compile("You are {{name}}; today is {{today}}.");
    assertThat(template.render(PromptVariables.of(Map.of("name", "Nessy", "today", "Monday"))))
        .isEqualTo("You are Nessy; today is Monday.");
  }

  @Test
  @DisplayName("a section shows when its variable is present and hides when it is empty")
  void sections_turn_on_presence() {
    PromptTemplate template =
        engine.compile(
            "Be brief.{{#persona}} You are {{persona}}.{{/persona}}{{^persona}} Be plain.{{/persona}}");
    assertThat(template.render(PromptVariables.of(Map.of("persona", "a butler"))))
        .isEqualTo("Be brief. You are a butler.");
    assertThat(template.render(PromptVariables.of(Map.of("persona", ""))))
        .isEqualTo("Be brief. Be plain.");
  }

  @Test
  @DisplayName("a hole nothing fills is refused rather than sent")
  void an_unfilled_hole_throws() {
    PromptTemplate template = engine.compile("You serve {{who}}.");
    PromptVariables none = PromptVariables.none();
    assertThatThrownBy(() -> template.render(none))
        .isInstanceOf(MustacheException.class)
        .hasMessageContaining("who");
  }
}
