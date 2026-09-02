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

import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Building harnesses — one per kind of agent")
class HarnessFactoryTest {

  /** Records which class it was asked to build a harness for, without building anything real. */
  private static final class RecordingFactory implements HarnessFactory {
    private Class<?> requestedObservationType;

    @Override
    public <O> Harness<O> createHarness(
        Class<O> observationType, Consumer<HarnessConfig<O>> configurer) {
      this.requestedObservationType = observationType;
      return null;
    }
  }

  @Test
  @DisplayName("the plain-text overload is sugar for the typed form keyed on String")
  void the_plain_text_overload_asks_for_a_string_backed_harness() {
    RecordingFactory factory = new RecordingFactory();

    factory.createHarness(config -> {});

    assertThat(factory.requestedObservationType).isEqualTo(String.class);
  }
}
