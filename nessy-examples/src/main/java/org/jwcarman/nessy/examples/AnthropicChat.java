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
package org.jwcarman.nessy.examples;

import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.Conversation;
import org.jwcarman.nessy.Reply;
import org.jwcarman.nessy.api.Event;
import org.jwcarman.nessy.api.event.SessionEvent;
import org.jwcarman.nessy.model.anthropic.AnthropicModelProvider;

/**
 * Pattern demonstrated: streaming via a raw hub subscription — {@code agent.events()}, filtered by
 * hand to this conversation's session id. Reach for this when you need the full event vocabulary
 * (every conversation on the agent, not just one), or events that outlive a single send.
 */
public final class AnthropicChat {

  private static final String API_KEY_ENV_VAR = "ANTHROPIC_API_KEY";
  private static final String MODEL = "claude-haiku-4-5-20251001";

  private AnthropicChat() {}

  public static void main(String[] args) {
    if (System.getenv(API_KEY_ENV_VAR) == null) {
      IO.println("Set " + API_KEY_ENV_VAR + " to run this example.");
      System.exit(1);
      return;
    }

    AnthropicModelProvider provider = AnthropicModelProvider.builder().fromEnv().build();
    Agent agent = DemoAgent.agentFor(provider, MODEL);
    Conversation conversation = agent.converse();
    agent
        .events()
        .subscribe(SessionEvent.class, sessionEvent -> render(sessionEvent, conversation));

    IO.println("Nessy demo (Anthropic, " + MODEL + "). Empty line or /quit to exit.");
    while (true) {
      String input = IO.readln("you> ");
      // IO.readln returns null at EOF (e.g. Ctrl-D on the console); treat that the same as
      // /quit rather than NPE-ing on the isBlank() check below.
      if (input == null || input.isBlank() || input.equals("/quit")) {
        return;
      }
      Reply reply = conversation.send(input);
      IO.println();
      if (reply.failed()) {
        IO.println("! " + reply.failureReason().orElse("unknown failure"));
      }
    }
  }

  private static void render(SessionEvent sessionEvent, Conversation conversation) {
    if (!sessionEvent.sessionId().equals(conversation.sessionId())) {
      return;
    }
    switch (sessionEvent.event()) {
      case Event.TextDelta textDelta -> IO.print(textDelta.text());
      case Event.ToolCallRequested toolCallRequested ->
          IO.println("\n⚙ tool: " + toolCallRequested.call().name());
      default -> {}
    }
  }
}
