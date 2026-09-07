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

import java.time.Clock;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.message.UserMessage;
import org.jwcarman.nessy.engine.EngineHarnessFactory;
import org.jwcarman.nessy.engine.ReplyTokens;
import org.jwcarman.nessy.model.discovery.ModelDiscovery;

/**
 * A terminal agent, from one call in a {@code main}.
 *
 * <pre>{@code
 * public static void main(String[] args) {
 *   Repl.run(config -> config
 *       .banner("nessy chat")
 *       .systemPrompt("You are a concise assistant living in someone's terminal.")
 *       .tool(new DaysUntilTool()));
 * }
 * }</pre>
 *
 * <p>Everything an engine needs is assembled here so an application does not have to: the model
 * comes from {@link ModelDiscovery}, which reads whichever credentials are in the environment, and
 * state lives in memory.
 *
 * <p>Every one of those is a DEFAULT, not a fixture. Anything an application may need to hold a
 * reference to — the substrate above all, since a notebook or a plan is opened over one — it can
 * build itself and hand to {@link ReplConfig}. An easy button that is the only thing able to create
 * a component is not an easy button; it is a wall.
 *
 * <p><b>Nothing survives the process, deliberately.</b> A conversation typed into a terminal has no
 * reason to outlive the terminal, so the substrate is in memory, and reply tokens are minted from
 * an ephemeral key. That is the honest shape for a REPL and the wrong one for anything else — an
 * application that needs to survive a restart is not a console application, and should assemble an
 * {@link EngineHarnessFactory} itself or use the Spring Boot starter.
 */
public final class Repl {

  private Repl() {}

  /**
   * Runs a conversation until the person leaves, then releases everything it built.
   *
   * <p>Returns when the loop ends: an exit word, or end of input.
   *
   * @throws IllegalArgumentException if {@code customizer} is null
   */
  public static void run(ReplCustomizer customizer) {
    java.util.Objects.requireNonNull(customizer, "customizer must not be null");
    ReplConfig config = new ReplConfig();
    customizer.customize(config);
    run(config, ConsoleIo.standard());
  }

  /** The seam a test drives: a configured REPL against a console that need not be real. */
  static void run(ReplConfig config, ConsoleIo io) {
    ModelDiscovery.Selection chosen;
    try {
      chosen = ModelDiscovery.select();
    } catch (IllegalStateException nothingToTalkTo) {
      // Discovery's own message names every provider it knows and the variables each one reads, or
      // says which two are ambiguous. That is the whole useful content of this failure, and a stack
      // trace out of a main would only bury it. Caught around the ONE call that raises it, so a
      // later IllegalStateException from the engine still surfaces in full.
      io.write(nothingToTalkTo.getMessage() + System.lineSeparator());
      io.flush();
      return;
    }
    // Closed in reverse: the engine stops before the gateway it was calling, and the selection owns
    // the vendor's HTTP client, so letting it go is what releases the connection pool.
    try (ModelDiscovery.Selection selection = chosen;
        ExecutorService blocking = Executors.newVirtualThreadPerTaskExecutor();
        EngineHarnessFactory factory = factory(selection, blocking, config)) {
      Harness<String> harness = harness(factory, selection, config);
      new ReplLoop(harness, config.agentId(), config, io).run();
    }
  }

  private static EngineHarnessFactory factory(
      ModelDiscovery.Selection selection, Executor blocking, ReplConfig config) {
    Clock clock = Clock.systemUTC();
    return new EngineHarnessFactory(
        engine ->
            engine
                .models(selection.provider())
                .dataSource(config.dataSource())
                .maxTokens(config.maxTokens())
                .blocking(blocking)
                .clock(clock)
                // Ephemeral, and correct here: a token only has to outlive the process that
                // minted it, and this process IS the conversation.
                .replyTokens(ReplyTokens.ephemeral()));
  }

  private static Harness<String> harness(
      EngineHarnessFactory factory, ModelDiscovery.Selection selection, ReplConfig config) {
    return factory.createHarness(
        String.class,
        harness -> {
          harness
              .type(config.type())
              .systemPrompt(config.systemPrompt())
              .model(selection.model().id())
              .renderer(UserMessage::of);
          // Only when the caller said something: unset, the harness keeps its own default, and
          // setting it to that default here would just be a longer way of saying nothing.
          config.memory().ifPresent(harness::memory);
          config.tools().forEach(grant -> grant.accept(harness));
        });
  }
}
