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
package org.jwcarman.nessy.agent.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jwcarman.nessy.agent.Agent;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.AgentType;
import org.jwcarman.nessy.agent.Harness;
import org.jwcarman.nessy.agent.StalenessPolicy;
import org.jwcarman.nessy.agent.backlog.SubstrateBacklog;
import org.jwcarman.nessy.agent.codec.Codecs;
import org.jwcarman.nessy.agent.durable.SubstrateComputations;
import org.jwcarman.nessy.agent.memory.VerbatimMemory;
import org.jwcarman.nessy.agent.model.ProviderModelCallExecutor;
import org.jwcarman.nessy.agent.narrate.TurnNarrationAdapter;
import org.jwcarman.nessy.agent.spi.ObservationRenderer;
import org.jwcarman.nessy.agent.store.SubstrateAgentStateStore;
import org.jwcarman.nessy.agent.tool.RegistryToolCallExecutor;
import org.jwcarman.nessy.api.CompletionPolicy;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.spi.Memory;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelSettings;
import org.jwcarman.nessy.spi.substrate.Codec;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;

/** The front doors (§7.1). Builders wire existing seams; they never own machinery. */
public final class Nessy {

  // package-private: HarnessConfig (a sibling top-level class, mirroring ToolConfig) shares these.
  static final String PROVIDER_MUST_NOT_BE_NULL = "provider must not be null";
  static final String SETTINGS_MUST_NOT_BE_NULL = "settings must not be null";

  /**
   * The {@code String} door's trivial backlog codec: UTF-8 bytes, nothing more. Not a new public
   * type — just the {@link Codec} contract's cheapest instance, private to this class.
   */
  private static final Codec<String> STRING_CODEC =
      new Codec<>() {
        @Override
        public byte[] encode(String value) {
          Objects.requireNonNull(value, "value must not be null");
          return value.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String decode(byte[] bytes) {
          Objects.requireNonNull(bytes, "bytes must not be null");
          return new String(bytes, StandardCharsets.UTF_8);
        }
      };

  private Nessy() {}

  public static CliBuilder cli() {
    return new CliBuilder();
  }

  /**
   * The one door for a kept, immortal {@link Harness} (harness-first spec §2): the {@code String}
   * text door — today's renderer and ergonomics, unchanged. The customizer is the house style — the
   * same grammar {@link org.jwcarman.nessy.api.tool.Tool#of(Class,
   * org.jwcarman.nessy.api.tool.ToolCustomizer)} already teaches: {@code customizer} fills in a
   * live {@link HarnessConfig}, then this factory turns it into the finished, kept {@link Harness}.
   * No public {@code build()} survives here — this is the only place a {@link HarnessConfig} ever
   * turns into a {@link Harness}, so "Nessy is the harness's only compiler" is true by shape, not
   * just by javadoc.
   */
  public static Harness<String> harness(HarnessCustomizer<String> customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    HarnessConfig<String> config = new HarnessConfig<>();
    config.renderer = text -> List.of(new TextBlock(text));
    config.backlogCodec = STRING_CODEC;
    customizer.customize(config);
    return config.finish();
  }

  /**
   * The typed door (spec §2, §6.4): observations are {@code O}, not {@code String}. {@code
   * customizer} must supply the {@link ObservationRenderer} that turns an {@code O} into inference
   * content — the same seam {@link Harness} has always taken — via {@link
   * HarnessConfig#renderer(ObservationRenderer)}; the backlog codec is always {@link
   * Codec#json(ObjectMapper, Class)} over the config's pinned mapper and {@code observationType}.
   */
  public static <O> Harness<O> harness(Class<O> observationType, HarnessCustomizer<O> customizer) {
    Objects.requireNonNull(observationType, "observationType must not be null");
    Objects.requireNonNull(customizer, "customizer must not be null");
    HarnessConfig<O> config = new HarnessConfig<>();
    config.observationType = observationType;
    customizer.customize(config);
    return config.finish();
  }

  public static final class CliBuilder {

    private ModelProvider provider;
    private ModelSettings settings;
    private Memory memory;
    private String id = "cli";
    private String typeName = "cli";
    private List<Tool<?>> tools = List.of();
    private ExecutorService executor;
    private ObjectMapper objectMapper = new ObjectMapper();

    /** Reachable only through {@link Nessy#cli()}. */
    private CliBuilder() {}

    /** The model backend the scope talks to. */
    public CliBuilder provider(ModelProvider provider) {
      this.provider = Objects.requireNonNull(provider, PROVIDER_MUST_NOT_BE_NULL);
      return this;
    }

    /** The model call's tuning knobs — model id, system prompt, token budget, capabilities. */
    public CliBuilder settings(ModelSettings settings) {
      this.settings = Objects.requireNonNull(settings, SETTINGS_MUST_NOT_BE_NULL);
      return this;
    }

    /** The conversation store; defaults to a fresh {@link VerbatimMemory} per build. */
    public CliBuilder memory(Memory memory) {
      this.memory = Objects.requireNonNull(memory, "memory must not be null");
      return this;
    }

    /** The scope's constant agent id. */
    public CliBuilder id(String id) {
      this.id = Objects.requireNonNull(id, "id must not be null");
      return this;
    }

    /** The recipe's name — the first coordinate of every durable address. */
    public CliBuilder type(String typeName) {
      this.typeName = Objects.requireNonNull(typeName, "typeName must not be null");
      return this;
    }

    /** The tools the model may call during a turn. */
    public CliBuilder tools(Tool<?>... tools) {
      this.tools = List.of(Objects.requireNonNull(tools, "tools must not be null"));
      return this;
    }

    /** A caller-supplied executor; when omitted, the built agent owns and closes its own. */
    public CliBuilder executor(ExecutorService executor) {
      this.executor = Objects.requireNonNull(executor, "executor must not be null");
      return this;
    }

    /**
     * The mapper the host binds JSON with; default a fresh {@link ObjectMapper}. {@code build()}
     * pins a copy of it (spec §7: lower-camel property naming, tolerant reads, no default typing)
     * and threads that one pinned copy through every recipe that binds JSON — user-registered
     * modules and serializers survive the copy.
     */
    public CliBuilder objectMapper(ObjectMapper objectMapper) {
      this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
      return this;
    }

    public CliAgent build() {
      Objects.requireNonNull(provider, PROVIDER_MUST_NOT_BE_NULL);
      Objects.requireNonNull(settings, SETTINGS_MUST_NOT_BE_NULL);
      ObjectMapper pinned = Codecs.copyAndPin(objectMapper);
      boolean ownsExecutor = executor == null;
      ExecutorService exec = ownsExecutor ? Executors.newVirtualThreadPerTaskExecutor() : executor;
      Memory effectiveMemory = memory != null ? memory : new VerbatimMemory();
      var relay = new RelayTurnObserver();
      ToolRegistry registry = ToolRegistry.of(tools.toArray(Tool[]::new));
      ToolRegistry limited = ToolRegistry.limited(registry, CompletionPolicy.AWAITABLE);
      var agentId = AgentId.of(id);
      var agentType = AgentType.of(typeName);
      Substrate substrate = new InMemorySubstrate();
      var store = new SubstrateAgentStateStore(substrate, id, Clock.systemUTC(), pinned);
      var backlog = new SubstrateBacklog<>(substrate, id, 1024, STRING_CODEC, pinned);
      Harness<String> harness =
          Harness.of(
              agentType,
              text -> List.of(new TextBlock(text)),
              new TurnNarrationAdapter(relay),
              false,
              StalenessPolicy.never(),
              rawId -> effectiveMemory,
              rawId -> store,
              rawId -> backlog,
              scopeMemory ->
                  new ProviderModelCallExecutor(
                      provider, settings, limited, scopeMemory, relay, exec),
              scopeId ->
                  new RegistryToolCallExecutor(limited, agentType, scopeId, relay, exec, pinned),
              substrate,
              pinned,
              new SubstrateComputations(substrate, pinned));
      Agent<String> agent = harness.bind(agentId);
      return new CliAgent(agent, harness, relay, exec, ownsExecutor);
    }
  }
}
