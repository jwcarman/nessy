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

import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.Harness;
import org.jwcarman.nessy.api.approval.Approver;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.spi.memory.Memory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The nessy wiring — the simplicity test itself (design §4). The starter supplies every substrate
 * bean (provider, persistence, {@link Harness}); this module's own call is only identity: which
 * model, which prompt, which tools, and that every approval parks — the UI is the approver, one
 * line: {@link Approver#parkAll()}.
 *
 * <p>{@code ChatWebSmokeTest}'s {@code @TestConfiguration} {@code Harness} bean wins over the
 * starter's by {@code @ConditionalOnMissingBean}, so no {@code @Profile("!test")} split is needed
 * here anymore: the starter's real {@code Harness} simply backs off in the test context, and the
 * smoke test's Testcontainers datasource feeds the starter's own persistence autoconfiguration.
 */
@Configuration
public class ChatWebConfig {

  private static final String SYSTEM_PROMPT =
      "You are the demo shop's helpful assistant. Use your tool when a coupon is warranted.";

  @Bean
  Agent<String> agent(Harness harness, Memory memory) {
    return harness.agent(
        a ->
            a.name("chat-web")
                .model("claude-sonnet-4-5")
                .systemPrompt(SYSTEM_PROMPT)
                .memory(memory)
                .tools(ToolGrant.grant(new IssueCouponTool(), UsagePolicy.requireApproval()))
                .approver(Approver.parkAll()));
  }
}
