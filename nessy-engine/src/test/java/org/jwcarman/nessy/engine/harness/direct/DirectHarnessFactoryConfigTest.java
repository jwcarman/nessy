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
package org.jwcarman.nessy.engine.harness.direct;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayName("The direct harness factory's configuration")
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DirectHarnessFactoryConfigTest {

  @Test
  void a_factory_no_customizer_gave_a_backend_refuses_to_build_and_names_the_call_that_sets_one() {
    assertThatThrownBy(() -> DefaultDirectHarnessFactory.of(List.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("backend is required: factory(f -> f.backend(...))");
  }
}
