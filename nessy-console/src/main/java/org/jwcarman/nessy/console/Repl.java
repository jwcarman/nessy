package org.jwcarman.nessy.console;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.engine.harness.DefaultHarnessFactory;
import org.jwcarman.nessy.engine.tool.ReplyTokens;
import org.jwcarman.nessy.spi.inference.InferenceOptions;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** One call from a main method to a working terminal agent. */
public final class Repl {

  private static final String MODEL_PROPERTY = "nessy.model";

  private Repl() {}

  public static void run(ReplCustomizer customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    ReplConfig config = new ReplConfig();
    customizer.customize(config);
    run(config, ConsoleIo.standard());
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
      Optional<DataSource> dataSource =
          config
              .dataSource()
              .or(
                  () ->
                      Optional.ofNullable(
                          context.getBeanProvider(DataSource.class).getIfAvailable()));
      if (dataSource.isEmpty()) {
        say(
            io,
            "no database is configured: the engine keeps its agents in PostgreSQL, so set"
                + " SPRING_DATASOURCE_URL (and USERNAME/PASSWORD) or call dataSource(...)");
        return;
      }

      ConsoleNarration narration = new ConsoleNarration(config.agentId(), io);
      // Closed with the context: the factory owns the engine's timer and every harness it made.
      try (DefaultHarnessFactory factory =
          new DefaultHarnessFactory(
              engine ->
                  engine
                      .dataSource(dataSource.get())
                      .inference(provider, new InferenceOptions(model.get(), config.maxTokens()))
                      .narrator(narration)
                      // Ephemeral, and correct here: a token only has to outlive the process that
                      // minted it, and this process IS the conversation.
                      .replyTokens(ReplyTokens.ephemeral()))) {
        Harness<String> harness =
            factory.create(
                String.class,
                h -> {
                  h.agentType(config.type())
                      .systemPrompt(config.systemPrompt())
                      .observationRenderer(said -> List.of(new Block.Text(said)));
                  config.tools().forEach(grant -> grant.accept(h));
                });
        new ReplLoop(harness, config.agentId(), config, io, narration).run();
      }
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

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  static class ReplBootstrap {}
}
