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
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.engine.direct.DirectHarnessFactory;
import org.jwcarman.nessy.engine.schema.VictoolsInputSchemaGenerator;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.block.Block;
import org.jwcarman.nessy.spi.store.Schemas;
import org.jwcarman.nessy.spring.boot.NessyAutoConfiguration;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;

/** One call from a main method to a working terminal agent. */
public final class Repl {

  private static final String MODEL_PROPERTY = "nessy.model";

  private Repl() {}

  /**
   * For a program that is already a Spring Boot application, which is the ordinary case.
   *
   * <p>Everything this needs -- a provider, a database for whatever tools keep things, a model name
   * -- the application already has injected, so nothing is discovered here and no context is
   * raised. Hand over a factory and say what the agent is for.
   */
  public static void run(DirectHarnessFactory factory, String model, ReplCustomizer customizer) {
    Objects.requireNonNull(factory, "factory must not be null");
    Objects.requireNonNull(model, "model must not be null");
    Objects.requireNonNull(customizer, "customizer must not be null");
    ReplConfig config = new ReplConfig();
    customizer.customize(config);
    run(factory, model, config, ConsoleIo.standard());
  }

  /**
   * For a program that is not a Spring Boot application and does not want to become one.
   *
   * <p>Raises just enough Boot to find a provider and a database, then does what the other one
   * does. An application with its own context should hand over a factory instead.
   */
  public static void run(ReplCustomizer customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    ReplConfig config = new ReplConfig();
    customizer.customize(config);
    run(config, ConsoleIo.standard());
  }

  static void run(DirectHarnessFactory factory, String model, ReplConfig config, ConsoleIo io) {
    ConsoleNarration narration = new ConsoleNarration(config.agentId(), io);
    DirectHarness<String> harness =
        factory.<String>create(
            h -> {
              h.agentType(config.type())
                  .systemPrompt(config.systemPrompt())
                  .inputRenderer(said -> List.of(new Block.Text(said)))
                  .inference(in -> in.model(model).maxTokens(config.maxTokens()))
                  .listener(narration);
              config.tools().forEach(grant -> grant.accept(h));
            });
    new ReplLoop(harness, config.agentId(), config, io, narration).run();
  }

  /**
   * web-application-type NONE: the context exists to let Boot's own mechanism find an {@link
   * InferenceProvider} bean (and, unless one was configured here, a {@code DataSource}), not to
   * serve anything. Closed in the same try that closes everything else this call built.
   */
  static void run(ReplConfig config, ConsoleIo io) {
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(ReplBootstrap.class).web(WebApplicationType.NONE).run()) {
      InferenceProvider provider;
      try {
        provider = context.getBean(InferenceProvider.class);
      } catch (NoSuchBeanDefinitionException noProvider) {
        // Boot's own message names the bean type it could not find, or every candidate it found --
        // the whole useful content of this failure, and a stack trace out of a main would bury it.
        say(io, noProvider.getMessage());
        return;
      }
      Optional<String> model = model(context.getEnvironment());
      if (model.isEmpty()) {
        say(io, "no model is configured: set NESSY_MODEL to the name your provider should use");
        return;
      }
      // A database is the application's business now, not this one's. The terminal keeps the
      // conversation in memory because the process IS the conversation: a turn that has ended has
      // ended, and a CLI that resumed yesterday's chat would surprise the person typing into it.
      // A tool that wants to remember something still brings its own store.
      config.dataSource().ifPresent(Schemas::initialize);
      run(
          DirectHarnessFactory.inMemory(
              provider, new VictoolsInputSchemaGenerator(), JsonMapper.builder().build()),
          model.get(),
          config,
          io);
    }
  }

  private static Optional<String> model(Environment environment) {
    String name = environment.getProperty(MODEL_PROPERTY);
    return name == null || name.isBlank() ? Optional.empty() : Optional.of(name);
  }

  private static void say(ConsoleIo io, String message) {
    io.write(message + System.lineSeparator());
    io.flush();
  }

  /**
   * Enough Boot to find a provider, and no more. The engine's own auto-configuration is excluded --
   * it would want a DataSource bean, a model and a system prompt to build a factory this class
   * builds by hand -- and so is what depends on it.
   */
  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration(exclude = NessyAutoConfiguration.class)
  static class ReplBootstrap {}
}
