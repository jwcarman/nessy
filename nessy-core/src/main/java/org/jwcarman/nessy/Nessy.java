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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jwcarman.nessy.api.approval.ApproveEverything;
import org.jwcarman.nessy.api.approval.Approver;
import org.jwcarman.nessy.api.event.AgentEventListener;
import org.jwcarman.nessy.api.tool.MapToolRegistry;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.spi.ExecutionEngine;
import org.jwcarman.nessy.spi.InProcessEngine;
import org.jwcarman.nessy.spi.Reducer;
import org.jwcarman.nessy.spi.model.AgentConfig;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.session.InMemorySessionStore;
import org.jwcarman.nessy.spi.session.SessionStore;

/**
 * The front door.
 *
 * <p>Everything except the model has a default that works, so the smallest useful agent is a
 * provider and a model name. Every default here is a seam you can replace, which is the whole point
 * of the framework.
 */
public final class Nessy {

  private Nessy() {}

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {

    private static final int DEFAULT_MAX_TOKENS = 4096;

    private ModelProvider model;
    private String modelName;
    private String systemPrompt = "";
    private int maxTokens = DEFAULT_MAX_TOKENS;
    private Set<Capability> capabilities = Set.of();
    private ToolRegistry tools = MapToolRegistry.of();
    private Approver approver = new ApproveEverything();
    private SessionStore store = new InMemorySessionStore();
    private final List<AgentEventListener> listeners = new ArrayList<>();
    private int maxConsecutiveErrors = Reducer.DEFAULT_MAX_CONSECUTIVE_ERRORS;
    private ObjectMapper mapper = new ObjectMapper();

    private Builder() {}

    public Builder model(ModelProvider model) {
      this.model = model;
      return this;
    }

    public Builder modelName(String modelName) {
      this.modelName = modelName;
      return this;
    }

    public Builder systemPrompt(String systemPrompt) {
      this.systemPrompt = systemPrompt;
      return this;
    }

    public Builder maxTokens(int maxTokens) {
      this.maxTokens = maxTokens;
      return this;
    }

    /**
     * What this agent asks providers to use. Empty means "whatever the provider does by default".
     */
    public Builder capabilities(Set<Capability> capabilities) {
      this.capabilities = Set.copyOf(capabilities);
      return this;
    }

    public Builder tools(ToolRegistry tools) {
      this.tools = tools;
      return this;
    }

    public Builder approver(Approver approver) {
      this.approver = approver;
      return this;
    }

    public Builder store(SessionStore store) {
      this.store = store;
      return this;
    }

    public Builder listener(AgentEventListener listener) {
      this.listeners.add(listener);
      return this;
    }

    public Builder maxConsecutiveErrors(int maxConsecutiveErrors) {
      this.maxConsecutiveErrors = maxConsecutiveErrors;
      return this;
    }

    public Builder objectMapper(ObjectMapper mapper) {
      this.mapper = mapper;
      return this;
    }

    public ExecutionEngine build() {
      if (model == null) {
        throw new IllegalStateException("a model provider is required: call model(...)");
      }
      if (modelName == null || modelName.isBlank()) {
        throw new IllegalStateException("a model name is required: call modelName(...)");
      }
      return new InProcessEngine(
          model,
          tools,
          approver,
          store,
          listeners,
          new Reducer(maxConsecutiveErrors),
          new AgentConfig(modelName, systemPrompt, maxTokens, capabilities),
          mapper);
    }
  }
}
