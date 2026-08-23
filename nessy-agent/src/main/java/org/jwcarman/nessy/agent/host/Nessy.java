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
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.jwcarman.nessy.agent.Agent;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.Harness;
import org.jwcarman.nessy.agent.spi.ObservationRenderer;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.spi.Memory;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelSettings;
import org.jwcarman.nessy.spi.substrate.Codec;

/** The front doors (§7.1). Builders wire existing seams; they never own machinery. */
public final class Nessy {

  // package-private: HarnessConfig (a sibling top-level class, mirroring ToolConfig) shares these.
  static final String MODEL_MUST_NOT_BE_NULL = "model must not be null";
  static final String SYSTEM_PROMPT_MUST_NOT_BE_NULL = "systemPrompt must not be null";

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

    private Model model;
    private String systemPrompt;
    private ModelSettings settings;
    private Memory memory;
    private String id = "cli";
    private String typeName = "cli";
    private Consumer<HarnessConfig<String>> toolConfigurer = config -> {};
    private ExecutorService executor;
    private ObjectMapper objectMapper = new ObjectMapper();
    private InputStream in = System.in;
    private PrintStream out = System.out;

    /** Reachable only through {@link Nessy#cli()}. */
    private CliBuilder() {}

    /** The bound model handle the scope talks to (spec §7): already knows which model it runs. */
    public CliBuilder model(Model model) {
      this.model = Objects.requireNonNull(model, MODEL_MUST_NOT_BE_NULL);
      return this;
    }

    /** First-class, harness-level configuration (spec §3, §7); required, no settings fallback. */
    public CliBuilder systemPrompt(String systemPrompt) {
      this.systemPrompt = Objects.requireNonNull(systemPrompt, SYSTEM_PROMPT_MUST_NOT_BE_NULL);
      return this;
    }

    /**
     * The model call's OPTIONAL tuning knobs — max token budget, requested capabilities, context
     * window; default {@link ModelSettings#defaults()} when never called.
     */
    public CliBuilder settings(ModelSettings settings) {
      this.settings = Objects.requireNonNull(settings, "settings must not be null");
      return this;
    }

    /**
     * The conversation store; defaults to a fresh view over this build's own in-memory substrate
     * ({@link HarnessConfig#memoryFactory}'s own default) — a fresh, empty conversation every
     * build, exactly as before.
     */
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

    /**
     * The tools the model may call during a turn, each granted an answered-allow authority (sugar
     * over {@link org.jwcarman.nessy.api.tool.ToolRegistry#of(Tool...)}). Shares one slot with
     * {@link #grants(ToolGrant...)} — whichever of the two is called last wins; calling both is not
     * additive.
     */
    public CliBuilder tools(Tool<?>... tools) {
      Objects.requireNonNull(tools, "tools must not be null");
      this.toolConfigurer = config -> config.tools(tools);
      return this;
    }

    /**
     * The tool grants every turn carries, authority and all — the door a §5a approval-gated tool
     * needs, since {@link #tools(Tool...)}'s sugar only ever grants an answered-allow authority.
     * Shares one slot with {@link #tools(Tool...)} — whichever of the two is called last wins;
     * calling both is not additive.
     */
    public CliBuilder grants(ToolGrant... grants) {
      Objects.requireNonNull(grants, "grants must not be null");
      this.toolConfigurer = config -> config.grants(grants);
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

    /**
     * The built {@link Console}'s input; default {@link System#in}. Overriding it is how a
     * scripted-IO test — or an embedding app driving the console over piped streams instead of a
     * real terminal — replaces the console's terminal without touching {@link Console} itself.
     */
    public CliBuilder in(InputStream in) {
      this.in = Objects.requireNonNull(in, "in must not be null");
      return this;
    }

    /** The built {@link Console}'s output; default {@link System#out}. See {@link #in}. */
    public CliBuilder out(PrintStream out) {
      this.out = Objects.requireNonNull(out, "out must not be null");
      return this;
    }

    /**
     * Sugar composing a harness with a fresh {@link Console} (spec §3): builds the SAME kept {@link
     * Harness} every door in this class produces — through {@link
     * Nessy#harness(HarnessCustomizer)}, not a bespoke wiring of its own — so the cli door gets
     * exactly the generic door's correct fanout-routed narration and its {@link
     * org.jwcarman.nessy.agent.ComputationApprover}-backed §5a gate for free: an approval-requiring
     * tool call PARKS here exactly as it does everywhere else, which is what makes {@link
     * Console#approver()} reachable at all. (Fix round context: an earlier hand-rolled wiring here
     * pointed its {@link org.jwcarman.nessy.agent.narrate.TurnNarrationAdapter} straight at {@code
     * relay}, bypassing the harness's internal per-id fanout entirely — {@code AssistantSaid}/
     * {@code TurnEnded} reached {@code relay} but never an {@link Agent#subscribe}d observer, so
     * {@link Agent#ask} hung forever on a cli-built agent. Delegating to the generic door retires
     * that wiring outright: {@code relay}, passed below as this harness's {@code turnObserver()},
     * is composed as one more subscriber inside the SAME internal fanout every id-scoped subscriber
     * shares — {@code relay} and any {@code subscribe}d observer (including {@link Agent#ask}'s own
     * capture) each see every event exactly once, never twice, and neither starves the other.)
     */
    public Console build() {
      Objects.requireNonNull(model, MODEL_MUST_NOT_BE_NULL);
      Objects.requireNonNull(systemPrompt, SYSTEM_PROMPT_MUST_NOT_BE_NULL);
      ModelSettings effectiveSettings = settings != null ? settings : ModelSettings.defaults();
      boolean ownsExecutor = executor == null;
      ExecutorService exec = ownsExecutor ? Executors.newVirtualThreadPerTaskExecutor() : executor;
      var relay = new RelayTurnObserver();
      Memory constantMemory = memory;
      Harness<String> harness =
          Nessy.harness(
              config -> {
                config
                    .model(model)
                    .systemPrompt(systemPrompt)
                    .settings(effectiveSettings)
                    .type(typeName)
                    .executor(exec)
                    .objectMapper(objectMapper)
                    .turnObserver(relay);
                toolConfigurer.accept(config);
                if (constantMemory != null) {
                  config.memoryFactory(rawId -> constantMemory);
                }
              });
      Agent<String> agent = harness.bind(AgentId.of(id));
      return new Console(agent, harness, relay, in, out, exec, ownsExecutor);
    }
  }
}
