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
package org.jwcarman.nessy.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("An agent event listener")
class AgentEventListenerTest {

  private static final AgentType CHAT = new AgentType("chat");
  private static final AgentType WATCHMAN = new AgentType("watchman");
  private static final AgentId ONE = new AgentId(UUID.randomUUID());

  @Test
  @DisplayName("built from handlers hears the kinds it named, in order, and ignores the rest")
  void the_builder_dispatches_by_kind() {
    List<String> heard = new CopyOnWriteArrayList<>();
    AgentEventListener listener =
        AgentEventListener.of(
            c ->
                c.onTurnEnded((type, id, ended) -> heard.add("ended " + ended.turn().value()))
                    .onTurnEnded((type, id, ended) -> heard.add("and again"))
                    .onAnswered((type, id, answered) -> heard.add("said " + answered.text())));

    listener.on(CHAT, ONE, new AgentEvent.TurnEnded(new TurnId(3)));
    listener.on(CHAT, ONE, new AgentEvent.Thinking());
    listener.on(CHAT, ONE, new AgentEvent.Answered("hi"));

    assertThat(heard).containsExactly("ended 3", "and again", "said hi");
  }

  @Test
  @DisplayName("narrowed to an agent type hears nobody else's agents")
  void the_builder_filters_by_agent_type() {
    AtomicInteger heard = new AtomicInteger();
    AgentEventListener listener =
        AgentEventListener.of(
            c -> c.agentType(CHAT).onTurnEnded((type, id, ended) -> heard.incrementAndGet()));

    listener.on(WATCHMAN, ONE, new AgentEvent.TurnEnded(new TurnId(1)));
    listener.on(CHAT, ONE, new AgentEvent.TurnEnded(new TurnId(1)));

    assertThat(heard).hasValue(1);
  }

  @Test
  @DisplayName("made async is told on another thread, and a failure there is logged, not thrown")
  void async_runs_elsewhere_and_survives_a_failure() throws InterruptedException {
    CountDownLatch ran = new CountDownLatch(1);
    List<String> threads = new CopyOnWriteArrayList<>();
    AgentEventListener listener =
        ((AgentEventListener)
                (type, id, event) -> {
                  threads.add(Thread.currentThread().getName());
                  ran.countDown();
                  throw new IllegalStateException("the listener failed");
                })
            .async();

    // No exception reaches the caller, and the caller's thread is not the one that ran it.
    listener.on(CHAT, ONE, new AgentEvent.Thinking());

    assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(threads.getFirst()).isEqualTo("nessy-listener");
  }
}
