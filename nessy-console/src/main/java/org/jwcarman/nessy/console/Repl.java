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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.DirectHarnessFactory;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.backend.inmemory.InMemoryDirectBackend;
import org.jwcarman.nessy.backend.jdbc.Schemas;
import org.jwcarman.nessy.engine.harness.direct.DefaultDirectHarnessFactory;
import org.jwcarman.nessy.engine.schema.VictoolsJsonSchemaGenerator;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.spring.boot.QueuedHarnessAutoConfiguration;
import org.springframework.beans.BeansException;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** One call from a main method to a working terminal agent. */
public final class Repl {

  private static final String MODEL_PROPERTY = "nessy.model";
  private static final String PROVIDER_PROPERTY = "nessy.provider";

  private Repl() {}

  /**
   * For a program that is already a Spring Boot application, which is the ordinary case.
   *
   * <p>Everything this needs -- a provider, a database for whatever tools keep things, a model name
   * -- the application already has injected, so nothing is discovered here and no context is
   * raised. Hand over a factory and say what the agent is for.
   */
  public static void run(
      DirectHarnessFactory factory, String model, Customizer<ReplConfig> customizer) {
    Objects.requireNonNull(factory, "factory must not be null");
    Objects.requireNonNull(model, "model must not be null");
    Objects.requireNonNull(customizer, "customizer must not be null");
    ReplConfig config = new ReplConfig();
    customizer.customize(config);
    // Which of the factory's providers answers is the factory's own business -- an application
    // handing over a factory it built itself has already chosen, and there is no id here to ask
    // it by, so /config says nothing about it rather than claiming one.
    run(factory, Optional.empty(), model, config, ConsoleIo.standard());
  }

  /**
   * For a program that is not a Spring Boot application and does not want to become one.
   *
   * <p>Raises just enough Boot to find a provider and a database, then does what the other one
   * does. An application with its own context should hand over a factory instead.
   */
  public static void run(Customizer<ReplConfig> customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    ReplConfig config = new ReplConfig();
    customizer.customize(config);
    run(config, ConsoleIo.standard());
  }

  static void run(
      DirectHarnessFactory factory,
      Optional<String> provider,
      String model,
      ReplConfig config,
      ConsoleIo io) {
    ConsoleNarration narration = new ConsoleNarration(config.agentId(), io);
    DirectHarness<String, String> harness =
        factory.<String>create(
            config.type(),
            h -> {
              h.systemPrompt(config.systemPrompt())
                  .inputRenderer(said -> List.of(new Block.Text(said)))
                  .inference(in -> in.model(model).maxTokens(config.maxTokens()))
                  .listener(narration);
              config.tools().forEach(grant -> grant.customize(h));
            });
    new ReplLoop(
            harness,
            config.agentId(),
            config,
            io,
            narration,
            new ReplLoop.Diagnostics(provider, model, config.maxTokens()))
        .run();
  }

  /**
   * web-application-type NONE: the context exists to let Boot's own mechanism find an {@link
   * InferenceProvider} bean (and, unless one was configured here, a {@code DataSource}), not to
   * serve anything. Closed in the same try that closes everything else this call built.
   */
  static void run(ReplConfig config, ConsoleIo io) {
    ConfigurableApplicationContext started;
    try {
      started =
          new SpringApplicationBuilder(ReplBootstrap.class).web(WebApplicationType.NONE).run();
    } catch (BeansException refused) {
      // Whatever Spring said, verbatim. This catches EVERY startup failure, so naming one cause
      // would misdiagnose all the others; Boot's own message already names the bean it could not
      // build. Said rather than thrown only because a person at a terminal should not be handed a
      // stack trace out of main.
      say(io, String.valueOf(refused.getMessage()));
      return;
    }
    try (ConfigurableApplicationContext context = started) {
      Map<String, InferenceProvider> providers = context.getBeansOfType(InferenceProvider.class);
      if (providers.isEmpty()) {
        say(io, "no provider is configured: export a vendor key such as OPENAI_API_KEY");
        return;
      }
      String chosen = context.getEnvironment().getProperty(PROVIDER_PROPERTY);
      if (chosen == null || chosen.isBlank()) {
        say(
            io,
            "no provider is chosen: set NESSY_PROVIDER to one of "
                + providers.keySet().stream().sorted().toList());
        return;
      }
      // nessy.provider and nessy.model are a pair (the direct door's own auto-configuration
      // enforces that at startup), so a provider chosen here means a model is set too.
      String model = context.getEnvironment().getProperty(MODEL_PROPERTY);
      // A database is the application's business now, not this one's. The terminal keeps the
      // conversation in memory because the process IS the conversation: a turn that has ended has
      // ended, and a CLI that resumed yesterday's chat would surprise the person typing into it.
      // A tool that wants to remember something still brings its own store.
      config.dataSource().ifPresent(Schemas::initialize);
      // Both taken from the context rather than built here: Boot configures the mapper, the starter
      // configures the codec factory, and a terminal that made its own would write bytes by
      // different rules than the rest of the process it is running in.
      ObjectMapper mapper = context.getBean(ObjectMapper.class);
      CodecFactory codecs = context.getBean(CodecFactory.class);
      ProviderId providerId = ProviderId.of(chosen);
      run(
          DefaultDirectHarnessFactory.of(
              factory -> {
                factory
                    .backend(new InMemoryDirectBackend(codecs))
                    .schemas(new VictoolsJsonSchemaGenerator())
                    .mapper(mapper);
                providers.forEach(
                    (name, provider) -> factory.provider(ProviderId.of(name), provider));
                factory.inference(providerId, new InferenceOptions(model, config.maxTokens()));
              }),
          Optional.of(chosen),
          model,
          config,
          io);
    }
  }

  private static void say(ConsoleIo io, String message) {
    io.write(message + System.lineSeparator());
    io.flush();
  }

  /**
   * Enough Boot to find a provider, and no more.
   *
   * <p>The engine's own auto-configuration is NOT excluded any more. It used to want a DataSource
   * to create the schema, which a console has no use for; the schema moved to the JDBC backend's
   * auto-configuration, where it belongs, and what is left here -- the codec factory every store
   * asks for -- is exactly what this needs. Excluding it now would orphan the in-memory backend,
   * which asks for that same factory.
   *
   * <p><b>The queued door is excluded by name, and that is the point of its being separate.</b> A
   * console reads a line and waits for the answer, so it uses the direct door and has no database
   * at all. Nothing about the classpath can tell those two doors apart -- both factories live in
   * the engine -- so an application that wants one says which.
   */
  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration(exclude = QueuedHarnessAutoConfiguration.class)
  static class ReplBootstrap {}
}
