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
package org.jwcarman.nessy.console;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.message.UserMessage;
import org.jwcarman.nessy.api.model.ModelId;
import org.jwcarman.nessy.engine.EngineHarnessFactory;
import org.jwcarman.nessy.engine.ReplyTokens;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * A terminal agent, from one call in a {@code main}.
 *
 * <pre>{@code
 * public static void main(String[] args) {
 *   Repl.run(config -> config
 *       .banner("nessy chat")
 *       .systemPrompt("You are a concise assistant living in someone's terminal.")
 *       .tool(new DaysUntilTool()));
 * }
 * }</pre>
 *
 * <p>Everything an engine needs is assembled here so an application does not have to: the model
 * comes from a Boot context this call raises and tears down around itself — {@code
 * web-application-type} {@code NONE}, so nothing listens on a port — which is how a provider
 * module's own {@code @AutoConfiguration} finds whichever API key is in the environment and
 * contributes the {@link ModelProvider} bean. An application picks its vendor the ordinary Boot
 * way: choose which provider jar rides the classpath, then set that vendor's key. State lives in
 * memory.
 *
 * <p>Every one of those is a DEFAULT, not a fixture. Anything an application may need to hold a
 * reference to — the substrate above all, since a notebook or a plan is opened over one — it can
 * build itself and hand to {@link ReplConfig}. An easy button that is the only thing able to create
 * a component is not an easy button; it is a wall.
 *
 * <p><b>Nothing survives the process, deliberately.</b> A conversation typed into a terminal has no
 * reason to outlive the terminal, so the substrate is in memory, and reply tokens are minted from
 * an ephemeral key. That is the honest shape for a REPL and the wrong one for anything else — an
 * application that needs to survive a restart is not a console application, and should assemble an
 * {@link EngineHarnessFactory} itself or use the Spring Boot starter.
 */
public final class Repl {

  /** The property {@code NESSY_MODEL} binds to under Boot's relaxed env-var rules. */
  private static final String MODEL_PROPERTY = "nessy.model";

  private Repl() {}

  /**
   * Runs a conversation until the person leaves, then releases everything it built.
   *
   * <p>Returns when the loop ends: an exit word, or end of input.
   *
   * @throws IllegalArgumentException if {@code customizer} is null
   */
  public static void run(ReplCustomizer customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    ReplConfig config = new ReplConfig();
    customizer.customize(config);
    run(config, ConsoleIo.standard());
  }

  /** The seam a test drives: a configured REPL against a console that need not be real. */
  static void run(ReplConfig config, ConsoleIo io) {
    // web-application-type NONE: this call raises a context to let Boot's own mechanism find a
    // ModelProvider bean, not to serve anything. Closed in the same try that closes everything
    // else this call built, in reverse order: the engine stops before the gateway it was calling,
    // and the context owns that gateway, so closing it is what releases the vendor's HTTP client.
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(ReplBootstrap.class).web(WebApplicationType.NONE).run()) {
      ModelProvider models;
      try {
        models = context.getBean(ModelProvider.class);
      } catch (NoSuchBeanDefinitionException noProvider) {
        // Boot's own message already names the bean type it could not find (zero candidates) or
        // every candidate it found (more than one) — the whole useful content of this failure, and
        // a stack trace out of a main would only bury it.
        io.write(noProvider.getMessage() + System.lineSeparator());
        io.flush();
        return;
      }
      ModelId modelId = modelId(context.getEnvironment());
      if (modelId == null) {
        io.write(
            "no model id is configured: set NESSY_MODEL to the id your provider should use"
                + System.lineSeparator());
        io.flush();
        return;
      }
      try (ExecutorService blocking = Executors.newVirtualThreadPerTaskExecutor();
          EngineHarnessFactory factory = factory(models, blocking, config)) {
        Harness<String> harness = harness(factory, modelId, config);
        new ReplLoop(harness, config.agentId(), config, io).run();
      }
    }
  }

  private static ModelId modelId(Environment environment) {
    String id = environment.getProperty(MODEL_PROPERTY);
    return id == null || id.isBlank() ? null : ModelId.of(id);
  }

  private static EngineHarnessFactory factory(
      ModelProvider models, Executor blocking, ReplConfig config) {
    Clock clock = Clock.systemUTC();
    return new EngineHarnessFactory(
        engine ->
            engine
                .models(models)
                .dataSource(config.dataSource())
                .maxTokens(config.maxTokens())
                .blocking(blocking)
                .clock(clock)
                // Ephemeral, and correct here: a token only has to outlive the process that
                // minted it, and this process IS the conversation.
                .replyTokens(ReplyTokens.ephemeral()));
  }

  private static Harness<String> harness(
      EngineHarnessFactory factory, ModelId modelId, ReplConfig config) {
    return factory.createHarness(
        String.class,
        harness -> {
          harness
              .type(config.type())
              .systemPrompt(config.systemPrompt())
              .model(modelId)
              .renderer(UserMessage::of);
          // Only when the caller said something: unset, the harness keeps its own default, and
          // setting it to that default here would just be a longer way of saying nothing.
          config.memory().ifPresent(harness::memory);
          config.tools().forEach(grant -> grant.accept(harness));
        });
  }

  /**
   * The marker this call raises a Boot context from: no beans of its own, just the door
   * {@code @EnableAutoConfiguration} opens onto every {@code AutoConfiguration.imports} on the
   * runtime classpath — a provider module's registration among them. Package-private: nothing
   * outside this class ever names it.
   */
  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  static class ReplBootstrap {}
}
