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
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.concurrent.atomic.AtomicBoolean;
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
   * The default close releases nothing and throws nothing — so a gateway with no client to release,
   * and every test double, needs no {@code close()} of its own, and a try-with-resources over one
   * needs no catch (the default narrows {@link AutoCloseable#close()} to throw no checked
   * exception, which is the whole reason it is redeclared).
   */
  @Test
  void a_gateway_that_holds_nothing_closes_silently_and_repeatedly() {
    ModelProvider bare = new FirstBareModelProvider();

    assertThatCode(
            () -> {
              bare.close();
              bare.close();
            })
        .doesNotThrowAnyException();
  }

  @Test
  void a_gateway_can_be_used_in_a_try_with_resources_without_a_catch() {
    var closed = new AtomicBoolean();

    try (ModelProvider gateway = new ClosingModelProvider(closed)) {
      assertThat(gateway.name()).isEqualTo("ClosingModelProvider");
    }

    assertThat(closed).isTrue();
  }

  /**
   * Every method but {@link ModelProvider#name()} filled in, so instances exercise only the
   * interface's default. Two differently named implementations (rather than one, or two anonymous
   * ones — which the JLS gives an empty simple name) so the test proves {@code name()} tracks each
   * concrete class rather than returning a fixed string.
   */
  private abstract static class BareModelProvider implements ModelProvider {

    @Override
    public Model model(String id) {
      throw new UnsupportedOperationException("not exercised by this test");
    }
  }

  private static final class FirstBareModelProvider extends BareModelProvider {}

  private static final class SecondBareModelProvider extends BareModelProvider {}

  /** A gateway that DOES hold something — the shape every real vendor gateway now takes. */
  private static final class ClosingModelProvider extends BareModelProvider {

    private final AtomicBoolean closed;

    private ClosingModelProvider(AtomicBoolean closed) {
      this.closed = closed;
    }

    @Override
    public void close() {
      closed.set(true);
    }
  }
}
