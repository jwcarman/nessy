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

import java.util.Locale;
import java.util.Set;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.ApprovalContext;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.Approver;

/**
 * Asks the person who is sitting right there.
 *
 * <pre>{@code
 * config.tool(
 *     new SendEmailTool(),
 *     binding ->
 *         binding
 *             .approver(ConsoleApprover.atTheTerminal())
 *             .describer(input -> "Send an email to " + input.to()));
 * }</pre>
 *
 * <p><b>It answers immediately, and that is the whole difference from a web desk.</b> An approver
 * that defers hands the question to the outside world and takes a {@code ReplyToken} to be answered
 * later — the right shape when the person is elsewhere, might be asleep, and the answer must
 * survive a restart. Here the person is at the keyboard with the agent's own output still on their
 * screen, so there is nothing to park, nothing to persist, and nobody to notify. It prompts, waits
 * for a line, and returns {@link Awaited.Ready}.
 *
 * <p><b>Why reading the console here is safe.</b> Two things, and the second is easy to get wrong.
 * This runs on the engine's blocking executor while the REPL thread is parked waiting for the turn
 * to end, so only one thread is ever at the keyboard. And it reads the SAME {@link
 * ConsoleIo#standard()} the loop does — one reader over {@code System.in} — because a second {@code
 * BufferedReader} would not take turns with the first: the loop's reader reads ahead, so a private
 * one here sees end of input and denies every request, which is exactly what it did before this was
 * shared.
 *
 * <p><b>Silence is refusal.</b> End of input — a closed pipe, a Ctrl-D — denies rather than
 * approves, for the same reason a broken approver denies: there is no third answer, and nothing
 * that cannot ask a person should be able to act as though it did.
 */
public final class ConsoleApprover implements Approver {

  private static final Set<String> YES = Set.of("y", "yes");

  private final ConsoleIo io;

  ConsoleApprover(ConsoleIo io) {
    this.io = io;
  }

  /** An approver that asks at the real console. */
  public static ConsoleApprover atTheTerminal() {
    return new ConsoleApprover(ConsoleIo.standard());
  }

  @Override
  public Awaited<ApprovalResult> approve(ApprovalRequest request, ApprovalContext context) {
    // A leading newline because the model was very likely mid-sentence: a question appended to a
    // half-written answer reads as part of it.
    io.write(
        System.lineSeparator()
            + "  ⚠ "
            + request.description()
            + System.lineSeparator()
            + "    allow? [y/N] ");
    io.flush();
    String answer = io.readLine();
    if (answer == null) {
      return Awaited.ready(ApprovalResult.denied("there was nobody at the terminal to ask"));
    }
    if (YES.contains(answer.strip().toLowerCase(Locale.ROOT))) {
      return Awaited.ready(ApprovalResult.approved());
    }
    // The reason reaches the MODEL, which has to explain itself to the same person who just said
    // no — so it says what happened rather than quoting whatever they typed.
    return Awaited.ready(ApprovalResult.denied("the person at the terminal declined"));
  }
}
