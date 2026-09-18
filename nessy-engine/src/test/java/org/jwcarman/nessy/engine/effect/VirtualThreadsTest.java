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
package org.jwcarman.nessy.engine.effect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.engine.history.HistoryEntry;
import org.jwcarman.nessy.spi.inference.InferenceResult;

/**
 * An effect never holds a platform thread.
 *
 * <p>Every effect is a wait on somebody else's server -- a model for seconds, a person for days --
 * so the thread performing one is doing nothing but occupying a stack. A pool sized for CPUs would
 * be sized for the wrong resource entirely: enough agents in flight and dispatch stops while every
 * thread sits idle on a socket.
 *
 * <p><b>The engine decides this, not its caller.</b> {@code EffectDispatcher} creates its own
 * virtual-thread executor and nothing configures it away -- which is why this test asks an engine
 * wired by hand, with an ordinary scheduler, rather than asking a container what property it read.
 * The {@code TaskScheduler} an application supplies is only a timer that says when to look for due
 * work; it never performs any.
 */
class VirtualThreadsTest {

  @Test
  void an_effect_is_performed_on_a_virtual_thread() {
    AtomicBoolean virtual = new AtomicBoolean();
    AgentType type = new AgentType("virtual");
    AgentId agentId = new AgentId(UUID.randomUUID());

    try (EngineFixture engine =
        new EngineFixture(
            (_, _) -> {
              virtual.set(Thread.currentThread().isVirtual());
              return new InferenceResult.Answer(HistoryEntry.InferenceAnswered.text("done"));
            })) {

      Harness<String> harness =
          engine
              .harnesses()
              .create(
                  String.class,
                  config ->
                      config
                          .agentType(type)
                          .systemPrompt("You are a test assistant.")
                          .inference(in -> in.model("a-model"))
                          .effects(e -> e.pollInterval(Duration.ofMillis(50))));

      harness.observe(agentId, "hello");

      await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(
              () -> assertThat(engine.history().entriesFrom(type, agentId, 0)).hasSize(2));

      assertThat(virtual.get())
          .as("a model call that held a platform thread would starve every other agent")
          .isTrue();
    }
  }
}
