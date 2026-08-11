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

import java.util.Objects;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.Conversation;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.model.openai.OpenAiModelProvider;

/**
 * Pattern demonstrated: the same live narration as {@link AnthropicChat} — a {@link
 * org.jwcarman.nessy.api.turn.TurnObserver} handed straight to {@link Conversation#tell(Object,
 * org.jwcarman.nessy.api.turn.TurnObserver)}, printing the model's prose as it streams.
 */
public final class OpenAiChat {

  private static final String API_KEY_ENV_VAR = "OPENAI_API_KEY";
  private static final String MODEL = "gpt-4o-mini";

  private OpenAiChat() {}

  public static void main(String[] args) {
    if (System.getenv(API_KEY_ENV_VAR) == null) {
      IO.println("Set " + API_KEY_ENV_VAR + " to run this example.");
      System.exit(1);
      return;
    }

    OpenAiModelProvider provider = OpenAiModelProvider.builder().fromEnv().build();
    Agent<String> agent = DemoAgent.agentFor(provider, MODEL);
    Conversation<String> conversation = agent.converse();

    IO.println("Nessy demo (OpenAI, " + MODEL + "). Empty line or /quit to exit.");
    while (true) {
      String input = IO.readln("you> ");
      // IO.readln returns null at EOF (e.g. Ctrl-D on the console); treat that the same as
      // /quit rather than NPE-ing on the isBlank() check below.
      if (input == null || input.isBlank() || input.equals("/quit")) {
        return;
      }
      RunOutcome outcome = conversation.tell(input, OpenAiChat::render);
      IO.println();
      if (outcome.state().status() == ConversationStatus.FAILED) {
        IO.println(
            "! " + Objects.requireNonNullElse(outcome.state().failureReason(), "unknown failure"));
      }
    }
  }

  private static void render(TurnEvent event) {
    switch (event) {
      case TurnEvent.TextDelta textDelta -> IO.print(textDelta.text());
      case TurnEvent.ToolCallRequested toolCallRequested ->
          IO.println("\n⚙ tool: " + toolCallRequested.call().name());
      default -> {}
    }
  }
}
