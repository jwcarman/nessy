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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.InputRenderer;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

/**
 * {@code AgentConfig.intent(Class)}'s own wiring-time gate (design of record
 * 2026-08-16-authorization §7, Task 3b): a vocabulary must be a CONCRETE type that renders as a
 * JSON OBJECT schema, checked here rather than discovered later as a schema a provider rejects at
 * call time. Abstract types (interfaces, sealed or not, and abstract classes) are rejected too —
 * victools cannot render a polymorphic schema, so accepting one at wiring time would be silent
 * non-functionality at call time — and the one-field rule makes one-intent-per-agent true by
 * construction.
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

  enum Flavor {
    VANILLA,
    CHOCOLATE
  }

  record RefundIntent(String reason) {}

  static final class PlainPojo {
    private String name;

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }
  }

  sealed interface SealedVocabulary permits SealedA, SealedB {}

  record SealedA(String x) implements SealedVocabulary {}

  record SealedB(int y) implements SealedVocabulary {}

  interface PlainInterfaceVocabulary {}

  abstract static class AbstractVocabulary {
    private String reason;

    String reason() {
      return reason;
    }

    void reason(String reason) {
      this.reason = reason;
    }
  }

  @Nested
  class Rejected_shapes {

    @Test
    void a_bare_string_vocabulary_is_rejected_naming_the_type_and_a_record() {
      assertThatThrownBy(() -> builder().intent(String.class))
          .isInstanceOf(AgentConfigurationException.class)
          .hasMessageContaining("String")
          .hasMessageContaining("record");
    }

    @Test
    void a_primitive_int_vocabulary_is_rejected() {
      assertThatThrownBy(() -> builder().intent(int.class))
          .isInstanceOf(AgentConfigurationException.class)
          .hasMessageContaining("record");
    }

    @Test
    void a_boxed_integer_vocabulary_is_rejected_naming_the_type() {
      assertThatThrownBy(() -> builder().intent(Integer.class))
          .isInstanceOf(AgentConfigurationException.class)
          .hasMessageContaining("Integer")
          .hasMessageContaining("record");
    }

    @Test
    void an_enum_vocabulary_is_rejected_naming_the_type() {
      assertThatThrownBy(() -> builder().intent(Flavor.class))
          .isInstanceOf(AgentConfigurationException.class)
          .hasMessageContaining("Flavor")
          .hasMessageContaining("record");
    }

    @Test
    void a_list_vocabulary_is_rejected() {
      assertThatThrownBy(() -> builder().intent(List.class))
          .isInstanceOf(AgentConfigurationException.class)
          .hasMessageContaining("record");
    }

    @Test
    void an_array_vocabulary_is_rejected() {
      assertThatThrownBy(() -> builder().intent(String[].class))
          .isInstanceOf(AgentConfigurationException.class)
          .hasMessageContaining("record");
    }

    @Test
    void
        a_sealed_interface_of_records_is_rejected_naming_the_type_and_the_discriminator_fallback() {
      assertThatThrownBy(() -> builder().intent(SealedVocabulary.class))
          .isInstanceOf(AgentConfigurationException.class)
          .hasMessageContaining("SealedVocabulary")
          .hasMessageContaining("abstract")
          .hasMessageContaining("discriminator");
    }

    @Test
    void a_plain_unsealed_interface_is_rejected() {
      assertThatThrownBy(() -> builder().intent(PlainInterfaceVocabulary.class))
          .isInstanceOf(AgentConfigurationException.class)
          .hasMessageContaining("PlainInterfaceVocabulary")
          .hasMessageContaining("abstract");
    }

    @Test
    void an_abstract_class_is_rejected() {
      assertThatThrownBy(() -> builder().intent(AbstractVocabulary.class))
          .isInstanceOf(AgentConfigurationException.class)
          .hasMessageContaining("AbstractVocabulary")
          .hasMessageContaining("abstract");
    }
  }

  @Nested
  class Accepted_shapes {

    @Test
    void a_record_vocabulary_is_accepted() {
      var agent = builder();

      assertThat(agent.intent(RefundIntent.class)).isSameAs(agent);
    }

    @Test
    void a_pojo_vocabulary_is_accepted() {
      var agent = builder();

      assertThat(agent.intent(PlainPojo.class)).isSameAs(agent);
    }
  }

  @Nested
  class One_intent_per_agent {

    @Test
    void a_second_intent_call_is_a_wiring_time_error_naming_the_first_vocabulary() {
      var agent = builder().intent(RefundIntent.class);

      assertThatThrownBy(() -> agent.intent(PlainPojo.class))
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
