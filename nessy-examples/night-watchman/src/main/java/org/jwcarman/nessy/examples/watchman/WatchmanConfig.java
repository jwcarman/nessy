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
package org.jwcarman.nessy.examples.watchman;

import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.Harness;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.spi.memory.Memory;
import org.jwcarman.nessy.spi.memory.Transcript;
import org.jwcarman.nessy.spi.memory.TranscriptMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The nessy wiring — one bean, the agent (spec §5). {@code Harness} and {@code ModelProvider}
 * arrive from the starter's autoconfiguration over the in-memory defaults; identity is declared
 * here: the standing orders, the two always-allowed tools (no human in this loop, nothing parks),
 * and the {@link Memory#windowed(Memory, int)} bound over an in-memory {@link TranscriptMemory}.
 */
@Configuration
public class WatchmanConfig {

  private static final String SYSTEM_PROMPT =
      "You are the night watchman of a ship's engine room. Standing orders: each round, check"
          + " the vitals with your tool and compare them with your recent rounds. Normal bands:"
          + " boiler pressure 150-220 psi; bilge level below 35 cm; hull stress below 70 MPa."
          + " If all is well, report all quiet in one terse sentence. If a vital is out of band"
          + " or clearly trending toward it across rounds, raise the alarm decisively with your"
          + " alarm tool, then summarize why in one sentence.";

  @Bean
  Agent<String> agent(
      Harness harness, EngineRoom engineRoom, @Value("${watchman.window:40}") int window) {
    return harness
        .agent()
        .name("night-watchman")
        .model("claude-sonnet-4-5")
        .systemPrompt(SYSTEM_PROMPT)
        .memory(Memory.windowed(new TranscriptMemory(Transcript.inMemory()), window))
        .tools(
            ToolGrant.grant(new CheckVitalsTool(engineRoom), UsagePolicy.allow()),
            ToolGrant.grant(new RaiseAlarmTool(), UsagePolicy.allow()))
        .build();
  }
}
