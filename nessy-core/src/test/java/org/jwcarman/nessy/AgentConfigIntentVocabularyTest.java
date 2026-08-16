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
package org.jwcarman.nessy;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.InputRenderer;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

/**
 * {@code AgentConfig.intent(Class)}'s own config-level invariants (design of record
 * 2026-08-16-authorization §7, amended): the one-field rule that makes one-intent-per-agent true by
 * construction, and the ordinary null rejection every setter on this config carries. There is no
 * wiring-time gate on the vocabulary's shape here — {@code intentType} is an ordinary tool input
 * type, accepted the way every other tool's input type is (design ruling: a schema-shape check was
 * built, then withdrawn, because it could only ever check that the rendered schema is fillable,
 * never that the model's JSON binds back into an instance or round-trips through the intent store —
 * both the vocabulary author's own responsibility, caught if at all by the ordinary fail-closed
 * machinery at call time). {@link AgentConfigPrincipalAndIntentTest} covers the runtime behavior: a
 * concrete record vocabulary declares, stores, and reads back.
 */
class AgentConfigIntentVocabularyTest {

  private static final ModelProvider NEVER_CALLED =
      new ModelProvider() {
        @Override
        public ModelStream stream(ModelRequest request) {
          throw new AssertionError("never called");
        }

        @Override
        public Set<Capability> capabilities() {
          return Set.of();
        }
      };

  private AgentConfig<String> builder() {
    return new AgentConfig<>(
            Nessy.harness(h -> h.provider(NEVER_CALLED)), String.class, InputRenderer.text())
        .name("scribe")
        .model("fake-model");
  }

  record RefundIntent(String reason) {}

  record OtherIntent(String note) {}

  @Nested
  class One_intent_per_agent {

    @Test
    void a_second_intent_call_is_a_wiring_time_error_naming_the_first_vocabulary() {
      var agent = builder().intent(RefundIntent.class);

      assertThatThrownBy(() -> agent.intent(OtherIntent.class))
          .isInstanceOf(AgentConfigurationException.class)
          .hasMessageContaining("RefundIntent");
    }
  }

  @Nested
  class Null_rejection {

    @Test
    void a_null_intent_type_is_rejected() {
      var agent = builder();

      assertThatThrownBy(() -> agent.intent(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("intentType");
    }

    @Test
    void a_null_principal_resolver_is_rejected() {
      var agent = builder();

      assertThatThrownBy(() -> agent.principal(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("resolver");
    }
  }
}
