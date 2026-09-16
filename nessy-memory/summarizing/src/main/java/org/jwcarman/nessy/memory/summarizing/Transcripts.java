package org.jwcarman.nessy.memory.summarizing;

import java.util.List;
import java.util.stream.Collectors;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.turn.Exchange;
import org.jwcarman.nessy.api.turn.Observation;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.api.turn.TurnResult;

/**
 * How a run of turns is shown to a summarising model, shared by every summariser: one line per
 * thing that happened, and the closing ask that gives the model something to answer.
 */
public final class Transcripts {

  private Transcripts() {}

  /** How the head is rendered for the model: one line per thing that happened. */
  public static String render(List<Turn> turns) {
    StringBuilder out = new StringBuilder();
    for (Turn turn : turns) {
      out.append("user: ").append(text(turn.observation().blocks())).append('\n');
      for (Exchange exchange : turn.exchanges()) {
        String said = text(exchange.request());
        if (!said.isBlank()) {
          out.append("assistant: ").append(said).append('\n');
        }
        exchange
            .calls()
            .forEach(
                call ->
                    out.append("assistant called ")
                        .append(call.name().value())
                        .append(' ')
                        .append(call.arguments())
                        .append('\n'));
        exchange
            .outcomes()
            .forEach(
                outcome ->
                    out.append("tool: ")
                        .append(
                            switch (outcome) {
                              case ToolOutcome.Succeeded(var _, var blocks) -> text(blocks);
                              case ToolOutcome.Failed(var _, String message) ->
                                  "failed: " + message;
                              case ToolOutcome.Denied(var _, String reason) -> "denied: " + reason;
                            })
                        .append('\n'));
      }
      switch (turn.result()) {
        case TurnResult.Answered(var blocks) ->
            out.append("assistant: ").append(text(blocks)).append('\n');
        case TurnResult.Failed _ -> out.append("(the assistant could not answer)\n");
        case TurnResult.Refused _ -> out.append("(the assistant declined to answer)\n");
        case null -> {
          // Still under way; never summarised.
        }
      }
    }
    return out.toString();
  }

  /**
   * The turn that asks for the summary. Every turn being folded is complete, so without it the
   * conversation would end on the assistant's own words, and a model given nothing to answer
   * answers nothing.
   */
  public static Turn ask(Turn last, String instruction) {
    return new Turn(
        new TurnId(last.id().value() + 1),
        new Observation(new Seq(last.id().value() + 1), List.of(new Block.Text(instruction))),
        List.of(),
        null,
        0);
  }

  /** The words in a run of blocks: text and commentary joined by newlines, the rest skipped. */
  public static String text(List<? extends Block> blocks) {
    return blocks.stream()
        .map(
            block ->
                switch (block) {
                  case Block.Text(String text) -> text;
                  case Block.Commentary(String text) -> text;
                  case Block.Provider _, Block.ToolCall _ -> "";
                })
        .filter(text -> !text.isEmpty())
        .collect(Collectors.joining("\n"));
  }
}
