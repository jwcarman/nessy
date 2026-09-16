package org.jwcarman.nessy.spi.inference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.InputSchema;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.turn.Summary;
import org.jwcarman.nessy.spi.narration.AgentNarrator;
import org.jwcarman.nessy.spi.narration.Narrator;

@DisplayName("The inference vocabulary")
class InferenceTypesTest {

  private static final List<Block.ActionRequestContent> ONLY_PROSE =
      List.of(new Block.Commentary("thinking"));

  @Test
  void what_is_refused_where_it_is_written() {
    assertThatThrownBy(() -> new InferenceResult.Actions(ONLY_PROSE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least one call");
    assertThatThrownBy(() -> new ToolOffer(new ToolName("t"), " ", new InputSchema("{}")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new InferenceOptions(" ", 10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_context_knows_whether_it_carries_summaries() {
    InferenceContext bare = InferenceContext.of(List.of());
    InferenceContext summarised =
        new InferenceContext(
            List.of(Summary.text(new TurnId(1), new TurnId(3), "a")), List.of(), List.of());

    assertThat(bare.hasSummaries()).isFalse();
    assertThat(summarised.hasSummaries()).isTrue();
    assertThat(new Failure.Rejected("too long").reason()).isEqualTo("too long");
  }

  @Test
  void a_narrator_bound_to_an_agent_tells_the_full_narrator_who() {
    AtomicReference<String> heard = new AtomicReference<>();
    Narrator narrator =
        (agentType, agentId, event) ->
            heard.set(agentType.value() + "/" + event.getClass().getSimpleName());
    AgentType chat = new AgentType("chat");

    narrator.forAgent(chat, new AgentId(UUID.randomUUID())).narrate(new AgentEvent.Thinking());

    assertThat(heard).hasValue("chat/Thinking");
    assertThatCode(() -> AgentNarrator.silent().narrate(new AgentEvent.Thinking()))
        .doesNotThrowAnyException();
  }
}
