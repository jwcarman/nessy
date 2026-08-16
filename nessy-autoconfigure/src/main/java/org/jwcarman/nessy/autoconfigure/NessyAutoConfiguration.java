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
package org.jwcarman.nessy.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import org.jwcarman.nessy.Harness;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.jwcarman.nessy.spi.conversation.Parks;
import org.jwcarman.nessy.spi.intent.IntentStore;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.subagent.SubagentLinks;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

/**
 * The razor (design §17): substrate arrives by autoconfiguration, identity stays yours. A {@link
 * Harness} is infrastructure — the model provider, session store, observation registry, object
 * mapper, and seeded default model an application sets up once — so it is exactly what this class
 * assembles from whatever beans the classpath and configuration produced. An {@link
 * org.jwcarman.nessy.AgentConfig} is identity — model, system prompt, tools, policies, a particular
 * agent's own shape — and nothing in this module ever builds one: {@link
 * Harness#agent(org.jwcarman.nessy.AgentCustomizer) Harness.agent(customizer)} is always the
 * application's own call, never Boot's.
 *
 * <p>Runs after {@link AnthropicProviderAutoConfiguration}, {@link
 * OpenAiProviderAutoConfiguration}, {@link GeminiProviderAutoConfiguration}, and {@link
 * JdbcPersistenceAutoConfiguration} so whichever {@link ModelProvider}, {@link ConversationStore},
 * {@link Parks}, {@link SubagentLinks}, or {@link IntentStore} those produce are already in the
 * context by the time {@link #harness} runs. {@link
 * ConditionalOnBean @ConditionalOnBean(ModelProvider.class)} means this configuration stays inert
 * until some provider module is present and resolved; {@link
 * ConditionalOnMissingBean @ConditionalOnMissingBean(Harness.class)} means a user-declared {@link
 * Harness} bean always wins outright, this class never runs a second pass over it.
 *
 * <p>{@link org.jwcarman.nessy.spi.memory.Memory} is a Task 2 bean too, but it is not consumed
 * here: {@code Memory} is agent-scoped ({@link org.jwcarman.nessy.AgentConfig#memory}), not a
 * harness seam, so it stays available for the application's own agent bean to inject directly
 * rather than being wired through this class.
 */
@AutoConfiguration(
    after = {
      AnthropicProviderAutoConfiguration.class,
      OpenAiProviderAutoConfiguration.class,
      GeminiProviderAutoConfiguration.class,
      BedrockProviderAutoConfiguration.class,
      JdbcPersistenceAutoConfiguration.class
    })
@ConditionalOnBean(ModelProvider.class)
@EnableConfigurationProperties(NessyProperties.class)
public class NessyAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(Harness.class)
  Harness harness(
      ModelProvider provider,
      NessyProperties properties,
      ObjectProvider<ConversationStore> store,
      ObjectProvider<Parks> parks,
      ObjectProvider<SubagentLinks> subagentLinks,
      ObjectProvider<IntentStore> intentStore,
      ObjectProvider<ObservationRegistry> observations,
      ObjectProvider<ObjectMapper> mapper) {
    return Nessy.harness(
        h -> {
          h.provider(provider);
          store.ifAvailable(h::store);
          parks.ifAvailable(h::parks);
          subagentLinks.ifAvailable(h::subagentLinks);
          intentStore.ifAvailable(h::intentStore);
          observations.ifAvailable(h::observations);
          mapper.ifAvailable(h::mapper);
          if (StringUtils.hasText(properties.defaultModel())) {
            h.defaultModel(properties.defaultModel());
          }
        });
  }
}
