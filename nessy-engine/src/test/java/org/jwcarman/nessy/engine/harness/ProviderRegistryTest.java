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
package org.jwcarman.nessy.engine.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.engine.observability.ObservedInferenceProvider;
import org.jwcarman.nessy.inference.InferenceProvider;

class ProviderRegistryTest {

  private static final AgentType CHAT = new AgentType("chat");
  private static final ProviderId OPENAI = ProviderId.of("openai");
  private static final ProviderId XAI = ProviderId.of("xai");

  private final InferenceProvider openai = (request, narrator) -> null;
  private final InferenceProvider xai = (request, narrator) -> null;

  @Test
  void an_agent_type_naming_a_registered_id_gets_that_provider_observed() {
    ProviderRegistry registry = new ProviderRegistry();
    registry.register(OPENAI, openai);
    registry.register(XAI, xai);
    ProviderRegistry.Resolved observed = registry.observed(ObservationRegistry.NOOP);

    InferenceProvider resolved = observed.resolve(CHAT, XAI, OPENAI);

    assertThat(resolved).isInstanceOf(ObservedInferenceProvider.class);
    assertThat(resolved).isSameAs(observed.resolve(new AgentType("critic"), XAI, null));
  }

  @Test
  void an_agent_type_naming_nothing_gets_the_default() {
    ProviderRegistry registry = new ProviderRegistry();
    registry.register(OPENAI, openai);
    registry.register(XAI, xai);
    ProviderRegistry.Resolved observed = registry.observed(ObservationRegistry.NOOP);

    assertThat(observed.resolve(CHAT, null, OPENAI)).isSameAs(observed.resolve(CHAT, OPENAI, null));
  }

  @Test
  void an_unregistered_id_fails_naming_the_agent_type_and_what_is_registered() {
    ProviderRegistry registry = new ProviderRegistry();
    registry.register(OPENAI, openai);
    registry.register(XAI, xai);
    ProviderRegistry.Resolved observed = registry.observed(ObservationRegistry.NOOP);
    ProviderId claude = ProviderId.of("claude");

    assertThatThrownBy(() -> observed.resolve(CHAT, claude, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            "agent type 'chat' names provider 'claude', which is not registered;"
                + " registered: [openai, xai]");
  }

  @Test
  void no_id_and_no_default_fails_naming_the_agent_type_and_what_is_registered() {
    ProviderRegistry registry = new ProviderRegistry();
    registry.register(OPENAI, openai);
    ProviderRegistry.Resolved observed = registry.observed(ObservationRegistry.NOOP);

    assertThatThrownBy(() -> observed.resolve(CHAT, null, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            "agent type 'chat' names no provider and the factory has no default;"
                + " registered: [openai]");
  }

  @Test
  void registering_the_same_id_twice_fails_at_once() {
    ProviderRegistry registry = new ProviderRegistry();
    registry.register(OPENAI, openai);

    assertThatThrownBy(() -> registry.register(OPENAI, xai))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("provider 'openai' is already registered");
  }

  @Test
  void the_registered_ids_are_listed_in_registration_order() {
    ProviderRegistry registry = new ProviderRegistry();
    registry.register(XAI, xai);
    registry.register(OPENAI, openai);

    assertThat(registry.ids()).containsExactly(XAI, OPENAI);
  }
}
