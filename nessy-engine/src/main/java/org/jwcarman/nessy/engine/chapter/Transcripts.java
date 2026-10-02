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
package org.jwcarman.nessy.engine.chapter;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jwcarman.nessy.api.Stringifier;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ToolConfig;
import org.jwcarman.nessy.api.turn.Exchange;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.api.turn.TurnResult;

/**
 * How a run of turns is shown to a summarising model, shared by every summariser: one line per
 * thing that happened, in words.
 *
 * <p>A call is one line, {@code assistant did: <action> -- <how it ended>}, written from the lines
 * recorded when it was made and when it finished. The call's raw arguments and its raw result are
 * never shown.
 *
 * <p><b>A refused turn is the one line {@code (a message was withdrawn)}</b>, in place of the whole
 * turn: its input, and anything it did before it was refused, are not written. Every inference
 * adapter withholds a refused turn from later requests, because sending it again can get the new
 * request refused; the transcript is one text message, so it is withheld here.
 *
 * <p><b>Nothing said can pass for one of the engine's own lines.</b> Every line of what the user or
 * the assistant said after the first is indented four spaces (a blank line stays empty), so the
 * only lines that begin at the left margin are the ones written here: {@code user: }, {@code
 * assistant: }, {@code assistant did: } and the parenthesised notes.
 */
public final class Transcripts {

  /** A failure's message or a denial's reason: one line, both ends kept, cut like a result line. */
  private static final Stringifier<String> ONE_LINE =
      Stringifier.<String>byToString().dropMiddle(ToolConfig.DEFAULT_LINE_LIMIT);

  /** What a refused turn is written as, whole: none of what it held is shown. */
  private static final String WITHDRAWN = "(a message was withdrawn)";

  private static final Pattern LINE_BREAK = Pattern.compile("\\r\\n|\\n|\\r");

  private Transcripts() {}

  /**
   * The turns of a chapter, rendered for a model asked to summarise them: one line per thing that
   * happened.
   */
  public static String render(List<Turn> turns) {
    StringBuilder out = new StringBuilder();
    for (Turn turn : turns) {
      if (turn.result() instanceof TurnResult.Refused) {
        out.append(WITHDRAWN).append('\n');
        continue;
      }
      out.append("user: ").append(said(text(turn.input().blocks()))).append('\n');
      for (Exchange exchange : turn.exchanges()) {
        String said = text(exchange.request());
        if (!said.isBlank()) {
          out.append("assistant: ").append(said(said)).append('\n');
        }
        for (Block.ToolCall call : exchange.calls()) {
          out.append("assistant did: ")
              .append(exchange.actionOf(call.id()))
              .append(" -- ")
              .append(ending(exchange, call.id()))
              .append('\n');
        }
      }
      switch (turn.result()) {
        case TurnResult.Answered(var blocks) ->
            out.append("assistant: ").append(said(text(blocks))).append('\n');
        case TurnResult.Failed _ -> out.append("(the assistant could not answer)\n");
        case TurnResult.Refused _ -> {
          // Written whole, above, and never reaches here.
        }
        case null -> {
          // Still under way; never summarised.
        }
      }
    }
    return out.toString();
  }

  /**
   * What somebody said, with every line after the first indented four spaces, so that nothing said
   * can begin at the left margin. A blank line stays empty. Lines end at {@code \n}, {@code \r\n}
   * or {@code \r}.
   */
  private static String said(String text) {
    String[] lines = LINE_BREAK.split(text, -1);
    StringBuilder out = new StringBuilder(lines[0]);
    for (int i = 1; i < lines.length; i++) {
      out.append('\n');
      if (!lines[i].isEmpty()) {
        out.append("    ").append(lines[i]);
      }
    }
    return out.toString();
  }

  /** How one call ended, as the words that follow its action on its line. */
  private static String ending(Exchange exchange, CallId id) {
    ToolOutcome outcome =
        exchange.outcomes().stream()
            .filter(candidate -> candidate.callId().equals(id))
            .findFirst()
            .orElse(null);
    return switch (outcome) {
      case ToolOutcome.Succeeded _ -> {
        String result = exchange.resultOf(id).orElse("");
        yield result.isEmpty() ? "succeeded" : "succeeded: " + result;
      }
      case ToolOutcome.Failed(var _, String message) -> "failed: " + ONE_LINE.stringify(message);
      case ToolOutcome.Denied(var _, String reason) -> "denied: " + ONE_LINE.stringify(reason);
      case null -> "no outcome recorded";
    };
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
