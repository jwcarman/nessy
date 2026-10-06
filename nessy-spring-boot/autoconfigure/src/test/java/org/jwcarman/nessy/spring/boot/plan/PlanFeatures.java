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
package org.jwcarman.nessy.spring.boot.plan;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.AmbientSource;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.ChapterPolicy;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.HarnessConfig;
import org.jwcarman.nessy.api.MemorySource;
import org.jwcarman.nessy.api.StateSource;
import org.jwcarman.nessy.api.Summarizer;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.TurnPolicy;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.ResolvableType;

/** What the plan tests share: finding the feature bean and recording what it installs. */
final class PlanFeatures {

  private PlanFeatures() {}

  /**
   * The feature bean, resolved by its exact generic type, wildcard included, so no cast is needed.
   */
  static Customizer<HarnessConfig<?>> feature(AssertableApplicationContext context) {
    ResolvableType type =
        ResolvableType.forType(new ParameterizedTypeReference<Customizer<HarnessConfig<?>>>() {});
    ObjectProvider<Customizer<HarnessConfig<?>>> provider = context.getBeanProvider(type);
    return provider.getObject();
  }

  /**
   * Calls a tool with an input of the type it declares. {@link Class#cast} keeps this checked: no
   * unchecked cast, so no suppression.
   */
  static <I> ToolResult call(Tool<I> tool, AgentType type, AgentId agentId, Object input) {
    I typed = tool.inputType().cast(input);
    ToolCallRequest<I> request =
        new ToolCallRequest<>() {
          @Override
          public AgentType agentType() {
            return type;
          }

          @Override
          public AgentId agentId() {
            return agentId;
          }

          @Override
          public TurnId turn() {
            return new TurnId(1);
          }

          @Override
          public CallId callId() {
            return new CallId("c1");
          }

          @Override
          public IdempotencyKey idempotencyKey() {
            return IdempotencyKey.of(UUID.fromString("01999999-0000-7000-8000-000000000001"));
          }

          @Override
          public ToolName toolName() {
            return tool.name();
          }

          @Override
          public I input() {
            return typed;
          }

          @Override
          public Instant deadline() {
            return Instant.now().plusSeconds(30);
          }
        };
    Awaited<ToolResult> answer = tool.call(request);
    return ((Awaited.Ready<ToolResult>) answer).value();
  }

  /** A config that remembers what was installed on it, for an agent type the test chooses. */
  static final class Recording implements HarnessConfig<Recording> {
    private final AgentType type;
    final List<Tool<?>> tools = new ArrayList<>();
    final List<AmbientSource> ambients = new ArrayList<>();

    Recording(AgentType type) {
      this.type = type;
    }

    @Override
    public AgentType agentType() {
      return type;
    }

    @Override
    public Recording turnPolicy(TurnPolicy policy) {
      return this;
    }

    @Override
    public <T> Recording tool(Tool<T> tool) {
      tools.add(tool);
      return this;
    }

    @Override
    public Recording instructions(String text) {
      return this;
    }

    @Override
    public Recording memory(MemorySource source) {
      return this;
    }

    @Override
    public Recording state(StateSource source) {
      return this;
    }

    @Override
    public Recording ambient(AmbientSource source) {
      ambients.add(source);
      return this;
    }

    @Override
    public Recording chapterPolicy(ChapterPolicy policy) {
      return this;
    }

    @Override
    public Recording summarizer(Summarizer summarizer) {
      return this;
    }
  }
}
