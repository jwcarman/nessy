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

import com.google.genai.Client;
import org.junit.jupiter.api.Test;

class SdkIsOnTheClasspathTest {

  @Test
  void sdk_is_on_the_classpath() {
    // A bare builder().isNotNull() is tautological — Client.builder() always returns a
    // non-null Builder whether or not the SDK is really wired up. Building a real client
    // instead exercises java-genai's actual construction path, which fails loudly if the
    // dependency (or one of its transitives) is missing.
    assertThat(Client.builder().apiKey("x").build()).isNotNull();
  }
}
