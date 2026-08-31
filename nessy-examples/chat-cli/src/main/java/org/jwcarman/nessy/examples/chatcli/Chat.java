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
package org.jwcarman.nessy.examples.chatcli;

import java.time.Clock;
import java.time.LocalDate;
import org.jwcarman.nessy.console.ConsoleApprover;
import org.jwcarman.nessy.console.Repl;

/**
 * A conversation in a terminal, with one tool.
 *
 * <p>This is the whole application. {@code nessy-console} owns everything that used to be here —
 * discovering the model from the environment, forming the actor system's cluster of one, the
 * in-memory substrate and reply tokens, and the loop that streams an answer as it arrives — so what
 * is left is the only part that is actually about THIS program: what it is for, and what it can do.
 *
 * <p>Which model it talks to is not decided here either. Set {@code ANTHROPIC_API_KEY} or {@code
 * GEMINI_API_KEY} or {@code XAI_API_KEY}, or point {@code OPENAI_BASE_URL} at a local runtime; the
 * banner says which one won. See the README.
 */
public final class Chat {

  /**
   * Says the date outright, and ALSO grants a tool for it.
   *
   * <p>Belt and braces on purpose. A model has a training cutoff and a strong prior about what year
   * it is: asked to help with Christmas shopping it will name whichever year it learned about and
   * reason confidently from that wrong anchor, never calling a tool to check — a tool only helps if
   * the model thinks to use it. Saying it here costs one line and cannot be skipped; the {@code
   * today} tool covers the case where a conversation outlives the prompt that started it.
   */
  private static String systemPrompt(LocalDate today) {
    return """
        You are a concise, friendly assistant living in someone's terminal. Keep answers short \
        unless asked for more.

        Today is %s. When a question turns on the current date or on counting days, use the \
        today and days_until tools rather than working it out yourself — and never assume the \
        year."""
        .formatted(today);
  }

  private Chat() {}

  public static void main(String[] args) {
    Clock clock = Clock.systemDefaultZone();
    Repl.run(
        config ->
            config
                .banner("nessy chat — Ctrl-D or /quit to leave")
                .prompt("> ")
                .exitOn("/quit", "quit", "exit")
                .farewell("bye.")
                .systemPrompt(systemPrompt(LocalDate.now(clock)))
                .tool(new TodayTool(clock))
                .tool(new DaysUntilTool())
                // The only thing here that reaches outside the process, so the only thing a
                // person is asked about. The describer writes the sentence they consent to.
                .tool(
                    new SendEmailTool(),
                    binding ->
                        binding
                            .approver(ConsoleApprover.atTheTerminal())
                            .describer(
                                input ->
                                    "Send an email to %s, subject \"%s\""
                                        .formatted(input.to(), input.subject()))));
  }
}
