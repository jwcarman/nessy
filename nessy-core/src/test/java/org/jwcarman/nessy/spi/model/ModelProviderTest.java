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
package org.jwcarman.nessy.spi.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModelProviderTest {

  @Test
  void the_default_name_is_each_implementations_own_simple_class_name() {
    ModelProvider first = new FirstBareModelProvider();
    ModelProvider second = new SecondBareModelProvider();

    assertThat(first.name()).isEqualTo("FirstBareModelProvider");
    assertThat(second.name()).isEqualTo("SecondBareModelProvider");
  }

  /**
   * Every method but {@link ModelProvider#name()} filled in, so instances exercise only the
   * interface's default. Two differently named implementations (rather than one, or two anonymous
   * ones — which the JLS gives an empty simple name) so the test proves {@code name()} tracks each
   * concrete class rather than returning a fixed string.
   */
  private abstract static class BareModelProvider implements ModelProvider {

    @Override
    public ModelStream stream(ModelRequest request) {
      return new ModelStream() {
        @Override
        public Iterator<ModelEvent> iterator() {
          return List.<ModelEvent>of().iterator();
        }

        @Override
        public void close() {
          // fake stream holds no resources to release
        }
      };
    }

    @Override
    public Set<Capability> capabilities() {
      return Set.of();
    }
  }

  private static final class FirstBareModelProvider extends BareModelProvider {}

  private static final class SecondBareModelProvider extends BareModelProvider {}
}
