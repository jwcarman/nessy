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
package org.jwcarman.nessy.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.observation.ObservationRegistry;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.AgentConfigurationException;
import org.jwcarman.nessy.Harness;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.conversation.AgendaItem;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.conversation.ParkedCall;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.jwcarman.nessy.testing.ScriptedModelProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@link NessyAutoConfiguration} against a runner combining all three substrate autoconfigurations
 * (both providers, persistence), the same trio {@link Harness} weaves together in a real
 * application. Each test isolates one seam: a provider alone is enough for a harness on defaults; a
 * store bean, an {@link ObservationRegistry} bean, and {@code nessy.default-model} are each woven
 * in when present; a user-declared {@link Harness} always wins outright.
 */
class NessyAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  AnthropicProviderAutoConfiguration.class,
                  OpenAiProviderAutoConfiguration.class,
                  JdbcPersistenceAutoConfiguration.class,
                  NessyAutoConfiguration.class))
          .withPropertyValues("nessy.anthropic.api-key=test-key");

  @Test
  void a_provider_alone_yields_a_harness_on_defaults() {
    runner.run(context -> assertThat(context).hasSingleBean(Harness.class));
  }

  @Test
  void a_store_bean_is_woven_in() {
    // A real Task-2 JDBC store needs a real Postgres connection to query without throwing
    // (nessy_park's schema uses a jsonb column H2 cannot parse, and the module's own JDBC-backed
    // tests are Testcontainers-based and tagged "container" for exactly that reason — out of
    // scope for this offline context runner). So the observable seam here is a hand-instrumented
    // ConversationStore, standing in for "the Task 2 store bean," that records whether the woven
    // harness actually reaches it: a broken wiring would leave the probe untouched (or throw),
    // not merely return empty from an unrelated in-memory default.
    var probe = new ProbeConversationStore();
    runner
        .withBean("mine", ConversationStore.class, () -> probe)
        .run(
            context -> {
              assertThat(context).hasSingleBean(Harness.class);
              Harness harness = context.getBean(Harness.class);
              assertThat(probe.peeked()).isFalse();
              assertThat(harness.peek(ParkToken.generate())).isEmpty();
              assertThat(probe.peeked()).isTrue();
            });
  }

  @Test
  void a_present_observation_registry_does_not_break_harness_assembly() {
    // ObservationRegistry#ifAvailable is a package-private wire-through with no public accessor
    // to reflect into, same limit NessyAutoConfigurationTest hits everywhere else in this class —
    // the cheap, non-reflective proof available offline is that a present registry bean doesn't
    // throw building the Harness. The registry's spans actually joining Boot's own HTTP/JDBC
    // trace is chat-web's Grafana story (its docker-compose observability stack), not something
    // an offline ApplicationContextRunner can observe.
    runner
        .withBean(ObservationRegistry.class, ObservationRegistry::create)
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void nessy_default_model_reaches_the_harness() {
    // Harness#defaultModel() is package-private and Harness exposes no public accessor for it, so
    // the proof has to go through behavior instead of a getter: AgentBuilder#build() requires a
    // model from somewhere (an explicit agent().model(...) call or the harness's own
    // defaultModel), throwing AgentConfigurationException otherwise. The negative case first —
    // no nessy.default-model, agent().build() fails — establishes that this really is the
    // discriminating signal, not something build() would succeed at regardless.
    runner.run(
        context -> {
          Harness bare = context.getBean(Harness.class);
          assertThatThrownBy(() -> bare.agent().build())
              .isInstanceOf(AgentConfigurationException.class);
        });
    runner
        .withPropertyValues("nessy.default-model=claude-haiku")
        .run(
            context -> {
              Harness harness = context.getBean(Harness.class);
              assertThat(harness.agent().build()).isNotNull();
            });
  }

  @Test
  void a_user_declared_harness_wins() {
    Harness mine =
        Nessy.harness(ScriptedModelProvider.builder().text("hi").endTurn().build()).build();
    runner
        .withBean("mine", Harness.class, () -> mine)
        .run(context -> assertThat(context.getBean(Harness.class)).isSameAs(mine));
  }

  /**
   * Delegates every operation to a fresh {@link ConversationStore#inMemory()}, except {@link
   * #findPark} — the one call {@link Harness#peek} makes — which is also recorded in {@link
   * #peeked}, the observable proof {@link #a_store_bean_is_woven_in} reads instead of reflecting
   * into {@link Harness}'s private field.
   */
  private static final class ProbeConversationStore implements ConversationStore {

    private final ConversationStore delegate = ConversationStore.inMemory();
    private final AtomicBoolean peeked = new AtomicBoolean();

    boolean peeked() {
      return peeked.get();
    }

    @Override
    public Optional<Loaded> load(ConversationId id) {
      return delegate.load(id);
    }

    @Override
    public ConversationState save(ConversationState state, Collection<String> drainedAgendaIds) {
      return delegate.save(state, drainedAgendaIds);
    }

    @Override
    public void appendAgenda(ConversationId id, AgendaItem entry) {
      delegate.appendAgenda(id, entry);
    }

    @Override
    public Optional<ParkedCall> findPark(ParkToken token) {
      peeked.set(true);
      return delegate.findPark(token);
    }

    @Override
    public Optional<ConversationId> findParkConversation(ParkToken token) {
      return delegate.findParkConversation(token);
    }

    @Override
    public boolean consumeToken(ParkToken token) {
      return delegate.consumeToken(token);
    }
  }
}
