package org.jwcarman.nessy.prompt.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.prompt.PromptTemplate;
import org.jwcarman.nessy.prompt.PromptVariableSource;
import org.jwcarman.nessy.prompt.PromptVariables;
import org.jwcarman.nessy.prompt.TemplatedSystemPrompt;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.PropertyPlaceholderHelper;

@DisplayName("The Spring template engine")
class SpringPromptTemplateFactoryTest {

  private final SpringPromptTemplateFactory engine = new SpringPromptTemplateFactory();

  @Test
  void fills_placeholders_by_name() {
    PromptTemplate template = engine.compile("You are ${name}, and today is ${today}.");
    assertThat(template.render(PromptVariables.of(Map.of("name", "Nessy", "today", "Monday"))))
        .isEqualTo("You are Nessy, and today is Monday.");
  }

  @Test
  void a_default_fills_a_hole_nothing_else_does() {
    PromptTemplate template = engine.compile("Answer in ${language:English}.");
    assertThat(template.render(PromptVariables.none())).isEqualTo("Answer in English.");
    assertThat(template.render(PromptVariables.of(Map.of("language", "French"))))
        .isEqualTo("Answer in French.");
  }

  @Test
  void an_escaped_placeholder_is_left_as_written() {
    PromptTemplate template = engine.compile("Write \\${name} literally.");
    assertThat(template.render(PromptVariables.none())).isEqualTo("Write ${name} literally.");
  }

  @Test
  @DisplayName("a hole with no value and no default is refused rather than sent")
  void an_unfilled_hole_throws() {
    PromptTemplate template = engine.compile("You serve ${who}.");
    assertThatThrownBy(() -> template.render(PromptVariables.none()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("who");
  }

  @Test
  void delimiters_are_the_callers_to_choose() {
    var mustacheish =
        new SpringPromptTemplateFactory(
            new PropertyPlaceholderHelper("{{", "}}", ":", '\\', false));
    assertThat(mustacheish.compile("Hi {{name}}").render(PromptVariables.of(Map.of("name", "you"))))
        .isEqualTo("Hi you");
  }

  @Test
  @DisplayName("an Environment is a source of variables, the same for every agent")
  void the_environment_fills_holes() {
    MockEnvironment environment = new MockEnvironment().withProperty("app.persona", "a butler");
    var prompt =
        TemplatedSystemPrompt.of(
            engine,
            "You are ${app.persona}; today is ${today}.",
            EnvironmentVariables.of(environment),
            PromptVariableSource.supplied("today", () -> "Tuesday"));
    assertThat(prompt.forAgent(new AgentId(UUID.randomUUID())).value())
        .isEqualTo("You are a butler; today is Tuesday.");
  }
}
