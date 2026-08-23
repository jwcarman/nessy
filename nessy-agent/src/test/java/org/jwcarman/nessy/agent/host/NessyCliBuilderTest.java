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
package org.jwcarman.nessy.agent.host;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.tool.Tool;

class NessyCliBuilderTest {

  @Test
  void aNullModelIsRejectedByItsSetter() {
    var builder = Nessy.cli();
    assertThatThrownBy(() -> builder.model(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void aNullSystemPromptIsRejectedByItsSetter() {
    var builder = Nessy.cli();
    assertThatThrownBy(() -> builder.systemPrompt(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void aNullSettingsIsRejectedByItsSetter() {
    var builder = Nessy.cli();
    assertThatThrownBy(() -> builder.settings(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void aNullMemoryIsRejectedByItsSetter() {
    var builder = Nessy.cli();
    assertThatThrownBy(() -> builder.memory(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void aNullToolsArrayIsRejectedByItsSetter() {
    var builder = Nessy.cli();
    assertThatThrownBy(() -> builder.tools((Tool<?>[]) null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void aNullExecutorIsRejectedByItsSetter() {
    var builder = Nessy.cli();
    assertThatThrownBy(() -> builder.executor(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void aNullIdIsRejectedByItsSetter() {
    var builder = Nessy.cli();
    assertThatThrownBy(() -> builder.id(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void aNullObjectMapperIsRejectedByItsSetter() {
    var builder = Nessy.cli();
    assertThatThrownBy(() -> builder.objectMapper(null)).isInstanceOf(NullPointerException.class);
  }
}
