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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentSubscriber;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.message.UserMessage;
import org.jwcarman.nessy.api.model.ModelId;
import org.jwcarman.nessy.model.openai.OpenAiModelProvider;
import org.jwcarman.nessy.spi.model.ModelProvider;

/**
 * A conversation in a terminal: type, watch the answer arrive a token at a time, type again.
 *
 * <p>This is the smallest complete Nessy application. It exists to show the shape of one, so it
 * deliberately keeps the parts a real deployment would hide: {@link Runtime} assembles the engine
 * by hand, and everything below is the whole of what an application writes — a harness, a tool, a
 * subscriber, and a loop over stdin.
 *
 * <p><b>The loop looks synchronous and the engine is not.</b> {@link Harness#observe} returns the
 * moment the line is durably the agent's problem; the answer arrives later, on other threads, as
 * events. What makes a REPL out of that is the one thing here that waits: after posting a line this
 * blocks until it sees {@code TurnEnded}, so a person is never asked to type over a reply that is
 * still being written. An unattended application would simply not wait.
 */
public final class Chat {

  private static final AgentType TYPE = AgentType.of("chat");
  private static final AgentId AGENT = AgentId.of("cli");

  private static final String SYSTEM_PROMPT =
      """
      You are a concise, friendly assistant living in someone's terminal. Keep answers short \
      unless asked for more. When a question turns on today's date or on counting days, use the \
      days_until tool rather than working it out yourself.""";

  private Chat() {}

  public static void main(String[] args) throws IOException {
    String baseUrl = env("OPENAI_BASE_URL", "http://localhost:1234/v1");
    String apiKey = env("OPENAI_API_KEY", "not-needed");
    String model = env("NESSY_MODEL", "qwen/qwen3.6-35b-a3b");

    ModelProvider models =
        OpenAiModelProvider.create(config -> config.apiKey(apiKey).baseUrl(baseUrl));

    try (Runtime runtime = Runtime.start(models, 4096)) {
      Harness<String> harness =
          runtime
              .factory()
              .createHarness(
                  String.class,
                  config ->
                      config
                          .type(TYPE)
                          .systemPrompt(SYSTEM_PROMPT)
                          .model(ModelId.of(model))
                          .renderer(UserMessage::of)
                          .tool(new DaysUntilTool()));

      // One queue, one item: "the turn you are waiting for is over". A queue rather than a latch
      // because the REPL waits again on the next line, and a latch does not reset.
      BlockingQueue<AgentEvent.TurnEnded> finished = new ArrayBlockingQueue<>(1);
      harness.subscribe(AGENT, printing(finished));

      System.out.printf("nessy chat — %s at %s%n", model, baseUrl);
      System.out.println("Ctrl-D or /quit to leave.");

      BufferedReader in =
          new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
      String line;
      System.out.print("\n> ");
      System.out.flush();
      while ((line = in.readLine()) != null) {
        if (line.isBlank()) {
          System.out.print("> ");
          System.out.flush();
          continue;
        }
        if (line.strip().equals("/quit")) {
          break;
        }
        finished.clear();
        harness.observe(AGENT, line);
        awaitTurn(finished);
        System.out.print("\n> ");
        System.out.flush();
      }
      System.out.println("\nbye.");
    }
  }

  /**
   * What the person sees. Deltas print as they arrive, so the answer appears at the speed the model
   * writes it; tool calls announce themselves, because a pause with no explanation looks like a
   * hang.
   */
  private static AgentSubscriber printing(BlockingQueue<AgentEvent.TurnEnded> finished) {
    return AgentSubscriber.of(
        events ->
            events
                .onTextDelta(delta -> System.out.print(delta.text()))
                .onToolCallRequested(
                    call -> System.out.printf("%n  [calling %s]%n", call.toolName()))
                .onToolCallCompleted(
                    call -> System.out.printf("  [%s answered]%n", call.toolName()))
                // offer(), not put(): if nobody is waiting — the queue is full or the REPL has
                // moved on — dropping the notice is right, and blocking an engine thread is not.
                .onTurnEnded(finished::offer));
  }

  private static void awaitTurn(BlockingQueue<AgentEvent.TurnEnded> finished) {
    try {
      AgentEvent.TurnEnded ended = finished.poll(5, TimeUnit.MINUTES);
      if (ended == null) {
        System.out.println("\n  [no answer in five minutes; the agent may still be working]");
      } else {
        System.out.println();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }
}
