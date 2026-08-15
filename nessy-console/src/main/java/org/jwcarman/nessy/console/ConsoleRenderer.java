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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Objects;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;

/**
 * The default look: composed on {@link TurnObserver#builder()} — this module's own dogfood of the
 * same composition point {@code night-watchman}'s {@code Watchman} and {@code order-desk}'s {@code
 * OrderDesk} dogfooded before it. Assistant prose streams plain, thinking streams dim-italic, tool
 * activity gets one dim {@code ⚙ tool:} line per requested/completed/parked event (the parked line
 * carries the park token), and a failed turn ending gets one red line with its reason. A quiescent
 * ending ({@code COMPLETE}, {@code IDLE}, or {@code PARKED}) renders nothing here — {@link
 * ConsoleRepl} already leaves a blank line after every told turn.
 *
 * <p>Exposed as a factory rather than a class so it composes: {@code ConsoleRepl}'s default is this
 * observer; a caller wanting to add a concern (a transcript file, a metrics counter) can still
 * reach for {@link TurnObserver#builder()} directly and fold this factory's behavior in alongside
 * their own, the same composition {@link TurnObserver}'s own javadoc describes.
 */
public final class ConsoleRenderer {

  private static final String TOOL_MARKER = "⚙ tool: ";

  private ConsoleRenderer() {}

  /** Builds the default observer, writing every styled line to {@code writer}. */
  public static TurnObserver observer(Writer writer) {
    Objects.requireNonNull(writer, "writer must not be null");
    return TurnObserver.builder()
        .onTextDelta(delta -> write(writer, delta.text()))
        .onThinkingDelta(delta -> write(writer, Ansi.dim(Ansi.italic(delta.text()))))
        .onToolCallRequested(requested -> toolLine(writer, requested.call(), "requested"))
        .onToolCallCompleted(completed -> toolLine(writer, completed.call(), "completed"))
        .onToolCallParked(
            parked -> toolLine(writer, parked.call(), "parked (" + parked.token().value() + ")"))
        .onTurnEnded(ended -> turnEnded(writer, ended))
        .build();
  }

  private static void toolLine(Writer writer, ToolCall call, String suffix) {
    write(writer, "\n" + Ansi.dim(TOOL_MARKER + call.name() + " " + suffix) + "\n");
  }

  private static void turnEnded(Writer writer, TurnEvent.TurnEnded ended) {
    if (ended.status() == ConversationStatus.FAILED) {
      String reason = Objects.requireNonNullElse(ended.failureReason(), "unknown failure");
      write(writer, "\n" + Ansi.red("! " + reason) + "\n");
    }
  }

  private static void write(Writer writer, String text) {
    try {
      writer.write(text);
      writer.flush();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
