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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Objects;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.approval.ApprovalRequest;
import org.jwcarman.nessy.api.approval.Approver;

/**
 * The safety gate, worked by a human at the keyboard (design §4): prints the tool's own {@code
 * describe(...)} line — highlighted bold-yellow, the security beat that should pop — and reads y/n.
 * Garbage input reprompts rather than being read as a denial; only {@code y}/{@code n}
 * (case-insensitive, whitespace-trimmed) settle the question. End of input (an unattended pipe, a
 * closed terminal) is read as a denial, the same conservative default {@code y}/{@code n} garbage
 * used to fall back to before this reprompt behavior existed.
 *
 * <p>Moved in from {@code chat-cli} and {@code scout}'s byte-identical, package-private copies
 * (design §4): this is the one library version, public and tested, both examples now call instead
 * of hand-rolling their own.
 */
public final class ConsoleApprover implements Approver {

  private static final String DECLINED_REASON = "declined at the console";

  private final BufferedReader reader;
  private final Writer writer;

  /**
   * The real-console constructor: a thin wrap of {@link System#out}, and {@link ConsoleIo#stdin()}
   * rather than a fresh wrap of {@link System#in} — shared with {@link ConsoleRepl#run(Agent,
   * ReplCustomizer)}, so a mid-turn approval prompt reads from the same buffer the REPL loop does,
   * rather than each stealing from the other's read of stdin.
   */
  public ConsoleApprover() {
    this(ConsoleIo.stdin(), ConsoleIo.stdout());
  }

  /** The testability seam: every decision above is exercised headless against these streams. */
  ConsoleApprover(BufferedReader reader, Writer writer) {
    this.reader = Objects.requireNonNull(reader, "reader must not be null");
    this.writer = Objects.requireNonNull(writer, "writer must not be null");
  }

  @Override
  public Awaited<Decision> approve(ApprovalRequest request) {
    Objects.requireNonNull(request, "request must not be null");
    write("\n" + Ansi.bold(Ansi.yellow("approve: " + request.description())) + "\n");
    while (true) {
      write(Ansi.bold(Ansi.yellow("y/n> ")));
      String answer = readLine();
      if (answer == null) {
        return Awaited.ready(new Decision.Deny(DECLINED_REASON));
      }
      String trimmed = answer.trim();
      if (trimmed.equalsIgnoreCase("y")) {
        return Awaited.ready(Decision.allow());
      }
      if (trimmed.equalsIgnoreCase("n")) {
        return Awaited.ready(new Decision.Deny(DECLINED_REASON));
      }
      write("please answer y or n\n");
    }
  }

  private void write(String text) {
    try {
      writer.write(text);
      writer.flush();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private String readLine() {
    try {
      return reader.readLine();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
