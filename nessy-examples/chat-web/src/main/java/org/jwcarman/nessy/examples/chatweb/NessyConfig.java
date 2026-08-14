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
package org.jwcarman.nessy.examples.chatweb;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import javax.sql.DataSource;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.Harness;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.api.approval.Approver;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.model.anthropic.AnthropicModelProvider;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.jwcarman.nessy.spi.memory.Memory;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.store.jdbc.JdbcPersistence;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * The nessy wiring — the simplicity test itself (design §4). Every approval parks; the UI is the
 * approver, one line: {@link Approver#parkAll()}.
 *
 * <p>The dogfood point: {@link #harness(ModelProvider, ConversationStore, ObservationRegistry)}
 * takes Boot's own auto-configured {@link ObservationRegistry}, so nessy's model-call and tool
 * observations join Boot's HTTP and JDBC spans in the same trace (design §5a).
 *
 * <p>{@link #modelProvider()} and {@link #harness(ModelProvider, ConversationStore,
 * ObservationRegistry)} are both {@code @Profile("!test")}: constructing the real {@link
 * AnthropicModelProvider} calls {@code fromEnv()}, which throws without {@code ANTHROPIC_API_KEY}
 * set. The {@code test} profile (see {@code ChatWebSmokeTest}) supplies its own {@code Harness}
 * bean built on a scripted {@link ModelProvider} instead, so the container smoke test never needs a
 * real API key.
 */
@Configuration
public class NessyConfig {

  private static final String SYSTEM_PROMPT =
      "You are the demo shop's helpful assistant. Use your tool when a coupon is warranted.";

  @Bean
  JdbcPersistence persistence(DataSource dataSource, ObjectMapper mapper) {
    return JdbcPersistence.create(dataSource, mapper);
  }

  @Bean
  ConversationStore store(JdbcPersistence persistence) {
    return persistence.store();
  }

  @Bean
  Memory memory(JdbcPersistence persistence) {
    return persistence.memory();
  }

  @Bean
  @Profile("!test")
  ModelProvider modelProvider() {
    return AnthropicModelProvider.builder().fromEnv().build();
  }

  @Bean
  @Profile("!test")
  Harness harness(
      ModelProvider modelProvider, ConversationStore store, ObservationRegistry observations) {
    return Nessy.harness(modelProvider).store(store).observations(observations).build();
  }

  @Bean
  Agent<String> agent(Harness harness, Memory memory) {
    return harness
        .agent()
        .model("claude-sonnet-4-5")
        .systemPrompt(SYSTEM_PROMPT)
        .memory(memory)
        .tools(ToolGrant.grant(new IssueCouponTool(), UsagePolicy.requireApproval()))
        .approver(Approver.parkAll())
        .build();
  }
}
