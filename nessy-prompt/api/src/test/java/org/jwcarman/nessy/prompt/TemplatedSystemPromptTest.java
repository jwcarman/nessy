package org.jwcarman.nessy.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.SystemPromptSource;

@DisplayName("A templated system prompt")
class TemplatedSystemPromptTest {

  private static final AgentId ONE = new AgentId(UUID.randomUUID());
  private static final AgentId TWO = new AgentId(UUID.randomUUID());

  /** A one-hole engine, so the framework is tested without any real one. */
  private static final PromptTemplateFactory ENGINE =
      source ->
          variables ->
              variables
                  .variable("who")
                  .map(who -> source.replace("<who>", who))
                  .orElseThrow(() -> new IllegalArgumentException("nothing fills <who>"));

  @Test
  void renders_for_the_agent_asked_about() {
    PromptVariableSource perAgent =
        (agentId, name) ->
            Optional.of(agentId.equals(ONE) ? "James" : "somebody else")
                .filter(_ -> name.equals("who"));
    SystemPromptSource prompt = TemplatedSystemPrompt.of(ENGINE, "You serve <who>.", perAgent);

    assertThat(prompt.forAgent(ONE).value()).isEqualTo("You serve James.");
    assertThat(prompt.forAgent(TWO).value()).isEqualTo("You serve somebody else.");
  }

  @Test
  @DisplayName("sources are asked in order and the first answer wins")
  void sources_compose_in_order() {
    SystemPromptSource prompt =
        TemplatedSystemPrompt.of(
            ENGINE,
            "You serve <who>.",
            PromptVariableSource.of(Map.of("other", "x")),
            PromptVariableSource.of(Map.of("who", "the first")),
            PromptVariableSource.of(Map.of("who", "the second")));

    assertThat(prompt.forAgent(ONE).value()).isEqualTo("You serve the first.");
  }

  @Test
  @DisplayName("is rendered afresh every time, so a supplied value can change")
  void renders_afresh() {
    AtomicInteger calls = new AtomicInteger();
    SystemPromptSource prompt =
        TemplatedSystemPrompt.of(
            ENGINE,
            "Call <who>.",
            PromptVariableSource.supplied("who", () -> "number " + calls.incrementAndGet()));

    assertThat(prompt.forAgent(ONE).value()).isEqualTo("Call number 1.");
    assertThat(prompt.forAgent(ONE).value()).isEqualTo("Call number 2.");
  }

  @Test
  @DisplayName("a hole nothing fills is the engine's to refuse, and the refusal is not swallowed")
  void an_unfilled_hole_throws() {
    SystemPromptSource prompt = TemplatedSystemPrompt.of(ENGINE, "You serve <who>.");
    assertThatThrownBy(() -> prompt.forAgent(ONE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("who");
  }

  @Test
  void firstOf_with_no_sources_answers_nothing() {
    assertThat(PromptVariableSource.firstOf(List.of()).variable(ONE, "anything")).isEmpty();
  }
}
