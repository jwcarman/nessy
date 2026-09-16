package org.jwcarman.nessy.memory.summarizing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.turn.Exchange;
import org.jwcarman.nessy.api.turn.Observation;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.api.turn.TurnResult;
import org.jwcarman.nessy.spi.inference.InferenceOptions;

/** The head as the summarising model reads it: one line per thing that happened. */
@DisplayName("The transcript a summary is written from")
class HeadSummarizerTranscriptTest {

  private static Observation asked(long seq, String text) {
    return new Observation(new Seq(seq), List.of(new Block.Text(text)));
  }

  @Test
  void every_kind_of_thing_that_can_happen_in_a_turn_has_a_line() {
    Exchange exchange =
        new Exchange(
            new Seq(2),
            List.of(
                new Block.Commentary("let me look"),
                new Block.ToolCall("c1", "depth", "{\"lake\":\"ness\"}"),
                new Block.ToolCall("c2", "width", "{}"),
                new Block.ToolCall("c3", "age", "{}"),
                new Block.Provider("someone", "{}")),
            List.of(
                new ToolOutcome.Succeeded(new CallId("c1"), List.of(new Block.Text("230m"))),
                new ToolOutcome.Failed(new CallId("c2"), "no ruler"),
                new ToolOutcome.Denied(new CallId("c3"), "not permitted")));
    List<Turn> turns =
        List.of(
            new Turn(
                new TurnId(1),
                asked(1, "how deep?"),
                List.of(exchange),
                new TurnResult.Answered(List.of(new Block.Text("230 metres"))),
                0),
            new Turn(new TurnId(5), asked(5, "and?"), List.of(), new TurnResult.Failed(), 0),
            new Turn(new TurnId(7), asked(7, "rude?"), List.of(), new TurnResult.Refused(), 0),
            new Turn(new TurnId(9), asked(9, "still open"), List.of(), null, 0));

    String transcript = HeadSummarizer.transcript(turns);

    assertThat(transcript)
        .isEqualTo(
            """
            user: how deep?
            assistant: let me look
            assistant called depth {"lake":"ness"}
            assistant called width {}
            assistant called age {}
            tool: 230m
            tool: failed: no ruler
            tool: denied: not permitted
            assistant: 230 metres
            user: and?
            (the assistant could not answer)
            user: rude?
            (the assistant declined to answer)
            user: still open
            """);
  }

  @Test
  void the_tail_kept_verbatim_must_fit_inside_the_threshold() {
    org.jwcarman.nessy.api.AgentType type = new org.jwcarman.nessy.api.AgentType("chat");
    java.util.function.Consumer<HeadSummarizer.Config> complete =
        c ->
            c.agentType(type)
                .summaries(new JdbcSummaries(new org.postgresql.ds.PGSimpleDataSource(), type))
                .histories((agentType, agentId) -> null)
                .leases((kind, key, ttl, work) -> false)
                .inference((request, narrator) -> null, InferenceOptions.of("m"))
                .leaseTtl(Duration.ofSeconds(5));

    assertThatThrownBy(() -> HeadSummarizer.create(complete.andThen(c -> c.tail(4, 8))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("minTail");
    assertThatThrownBy(() -> HeadSummarizer.create(complete.andThen(c -> c.tail(4, 0))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> HeadSummarizer.create(c -> c.agentType(type)))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("summaries");
  }
}
