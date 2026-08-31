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

  private static final String SYSTEM_PROMPT =
      """
      You are a concise, friendly assistant living in someone's terminal. Keep answers short \
      unless asked for more. When a question turns on today's date or on counting days, use the \
      days_until tool rather than working it out yourself.""";

  private Chat() {}

  public static void main(String[] args) {
    Repl.run(
        config ->
            config
                .banner("nessy chat — Ctrl-D or /quit to leave")
                .prompt("> ")
                .exitOn("/quit", "quit", "exit")
                .farewell("bye.")
                .systemPrompt(SYSTEM_PROMPT)
                .tool(new DaysUntilTool()));
  }
}
