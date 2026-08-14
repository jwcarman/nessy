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
package org.jwcarman.nessy.examples.dispatcher;

import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.Harness;
import org.jwcarman.nessy.api.event.ToolProgress;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.spi.memory.Memory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The nessy wiring — one bean, the agent (spec §3). The starter supplies every substrate bean
 * (provider, {@link Harness}, JDBC-backed {@link Memory}); this module's own call is identity:
 * which model, the dispatcher's standing orders, the one tool granted {@link UsagePolicy#allow()}
 * (nothing here parks on a human — the crew parks the turn, not an approver), and a logging
 * listener on tool progress so the park token surfaces in the app log (spec §3's first surfacing
 * place; {@code GET /incidents/{id}} is the second).
 */
@Configuration
public class DispatcherConfig {

  private static final Logger LOGGER = LoggerFactory.getLogger(DispatcherConfig.class);

  private static final String SYSTEM_PROMPT =
      "You are an incident dispatcher. Signals arrive one line each. Triage tersely. For"
          + " actionable incidents, call your tool exactly once per incident unless told"
          + " otherwise. When the crew's outcome arrives, close out with a one-line summary.";

  @Bean
  Agent<String> agent(Harness harness, Memory memory) {
    return harness
        .agent()
        .model("claude-sonnet-4-5")
        .systemPrompt(SYSTEM_PROMPT)
        .memory(memory)
        .tools(ToolGrant.grant(new RequestFieldCrewTool(), UsagePolicy.allow()))
        .onToolProgressAsync(DispatcherConfig::logProgress)
        .build();
  }

  private static void logProgress(ToolProgress progress) {
    LOGGER.info("progress [{}]: {}", progress.conversationId().value(), progress.message());
  }
}
