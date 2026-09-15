package org.jwcarman.nessy.spring.boot.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.SystemPromptSource;
import org.jwcarman.nessy.prompt.PromptTemplateFactory;
import org.jwcarman.nessy.prompt.PromptVariableSource;
import org.jwcarman.nessy.prompt.mustache.MustachePromptTemplateFactory;
import org.jwcarman.nessy.prompt.spring.SpringPromptTemplateFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@DisplayName("The system prompt as a template")
class PromptAutoConfigurationTest {

  private static final AgentId ANY = new AgentId(UUID.randomUUID());

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  PromptEngineAutoConfiguration.class, PromptAutoConfiguration.class));

  @Test
  @DisplayName("is rendered from the properties, so ${app.persona} is whatever the properties say")
  void the_environment_fills_the_prompt() {
    runner
        .withPropertyValues(
            "nessy.system-prompt=You are ${app.persona}.", "app.persona=a lighthouse keeper")
        .run(
            context -> {
              assertThat(context)
                  .getBean(PromptTemplateFactory.class)
                  .isInstanceOf(SpringPromptTemplateFactory.class);
              assertThat(context.getBean(SystemPromptSource.class).forAgent(ANY).value())
                  .isEqualTo("You are a lighthouse keeper.");
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class ASource {
    @Bean
    PromptVariableSource persona() {
      return PromptVariableSource.of(Map.of("app.persona", "a butler"));
    }
  }

  @Test
  @DisplayName(
      "a source the application declares is asked before the properties (for a prompt file: Boot"
          + " resolves ${...} inside an inline property before the engine sees it)")
  void declared_sources_come_first(@TempDir Path dir) throws IOException {
    Path prompt = dir.resolve("prompt.txt");
    Files.writeString(prompt, "You are ${app.persona}.");
    runner
        .withUserConfiguration(ASource.class)
        .withPropertyValues("nessy.system-prompt-file=file:" + prompt, "app.persona=nobody")
        .run(
            context ->
                assertThat(context.getBean(SystemPromptSource.class).forAgent(ANY).value())
                    .isEqualTo("You are a butler."));
  }

  @Test
  @DisplayName("nessy.prompt.engine=mustache picks the other engine")
  void the_engine_is_a_property() {
    runner
        .withPropertyValues(
            "nessy.prompt.engine=mustache",
            "nessy.system-prompt=You are {{app.persona}}.",
            "app.persona=a lighthouse keeper")
        .run(
            context -> {
              assertThat(context)
                  .getBean(PromptTemplateFactory.class)
                  .isInstanceOf(MustachePromptTemplateFactory.class);
              assertThat(context.getBean(SystemPromptSource.class).forAgent(ANY).value())
                  .isEqualTo("You are a lighthouse keeper.");
            });
  }

  @Test
  @DisplayName("a hole nothing fills fails the render, loudly, not the startup")
  void an_unfilled_hole_fails_at_render() {
    runner
        .withPropertyValues("nessy.system-prompt=You are ${nobody.set.this}.")
        .run(
            context -> {
              SystemPromptSource prompt = context.getBean(SystemPromptSource.class);
              assertThatThrownBy(() -> prompt.forAgent(ANY))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("nobody.set.this");
            });
  }

  @Test
  @DisplayName("without an engine on the classpath there is no templated prompt")
  void without_an_engine_nothing_is_declared() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PromptAutoConfiguration.class))
        .withPropertyValues("nessy.system-prompt=plain")
        .run(context -> assertThat(context).doesNotHaveBean(SystemPromptSource.class));
  }
}
