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
package org.jwcarman.nessy.model.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.spi.model.Model;

/**
 * {@code gen_ai.provider.name} for this vendor (agentic-o11y spec §1.1).
 *
 * <p><b>{@code gcp.gemini}, not {@code gcp.vertex_ai}</b>: semconv separates the two Google
 * surfaces, and this module talks to the Gemini Developer API — {@link GeminiProviderConfig} builds
 * its java-genai {@code Client} from a plain API key and never enables Vertex AI's
 * project/location/credentials mode, as {@link GeminiModelProvider}'s own javadoc says ("Vertex
 * AI's project/location/credentials auth is out of scope for v1"). A module that later grows a
 * Vertex path must report the other value from the handle bound through it.
 */
class GeminiProviderNameTest {

  @Test
  void every_bound_model_reports_the_semconv_value_for_the_gemini_developer_api() {
    Model model = new GeminiProviderConfig().apiKey("key-test").build().model("gemini-2.5-pro");

    assertThat(model.provider()).isEqualTo("gcp.gemini");
    assertThat(GeminiModelProvider.PROVIDER).isEqualTo("gcp.gemini");
  }
}
