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
package org.jwcarman.nessy;

import static io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.micrometer.observation.tck.TestObservationRegistry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.ConversationEvent;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.jwcarman.nessy.spi.conversation.Parks;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;
import org.slf4j.LoggerFactory;

/**
 * {@code HarnessConfig}'s own setters — {@link HarnessConfig#observations}, {@link
 * HarnessConfig#mapper}, and its two {@code listenAsync} overloads — isolated from the
 * model-resolution and declared-{@code listen} stories {@code HarnessTest} already covers.
 */
class HarnessConfigTest {

  record Greeting(String text) {}

  /** A model that replays one scripted text turn per call and records every request it saw. */
  private static final class FakeProvider implements ModelProvider {

    private final Deque<String> replies;
    private final List<ModelRequest> requests = new ArrayList<>();

    FakeProvider(String... replies) {
      this.replies = new ArrayDeque<>(List.of(replies));
    }

    List<ModelRequest> requests() {
      return List.copyOf(requests);
    }

    @Override
    public ModelStream stream(ModelRequest request) {
      requests.add(request);
      List<ModelEvent> turn =
          List.of(
              new ModelEvent.TextChunk(replies.removeFirst()),
              new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()));
      Iterator<ModelEvent> events = turn.iterator();
      return new ModelStream() {
        @Override
        public Iterator<ModelEvent> iterator() {
          return events;
        }

        @Override
        public void close() {
          // intentionally empty: this fake stream holds no resources to release
        }
      };
    }

    @Override
    public Set<Capability> capabilities() {
      return Set.of();
    }
  }

  @Test
  void the_observations_override_receives_the_engines_own_observations() {
    TestObservationRegistry observations = TestObservationRegistry.create();
    FakeProvider provider = new FakeProvider("hi");
    Agent<String> agent =
        Nessy.harness(h -> h.provider(provider).observations(observations))
            .agent(a -> a.name("sentinel").model("fake-model"));

    agent.converse().tell("hi");

    assertThat(observations)
        .hasObservationWithNameEqualTo("nessy.run")
        .that()
        .backToTestObservationRegistry()
        .hasObservationWithNameEqualTo("nessy.model.call");
  }

  @Test
  void the_mapper_override_is_used_for_the_default_json_renderer() {
    ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    FakeProvider provider = new FakeProvider("hi");
    Agent<Greeting> agent =
        Nessy.harness(h -> h.provider(provider).mapper(mapper))
            .agent(Greeting.class, a -> a.name("sentinel").model("fake-model"));

    agent.converse().tell(new Greeting("hi"));

    // The tagged-JSON renderer's body is exactly what the harness's own mapper produces: a tag
    // line, a newline, then the JSON. A plain ObjectMapper() would still put that one newline in,
    // but only an indenting mapper spreads the object itself across further lines of its own.
    var block =
        (TextBlock)
            provider.requests().getFirst().context().messages().getFirst().content().getFirst();
    assertThat(block.text().lines().count()).isGreaterThan(2);
  }

  @Nested
  class Required_provider {

    /**
     * Before this generation, omitting the provider was a compile error — {@code
     * Nessy.harness(ModelProvider)} demanded it by signature. Now a customizer can simply never
     * call {@link HarnessConfig#provider}, so {@link HarnessConfig#build()}'s own {@code
     * requireNonNull} is the only thing standing between a bare customizer and a harness with no
     * provider at all; this pins that it actually fires, naming the field, and never silently
     * defaults or drifts.
     */
    @Test
    void a_customizer_that_never_calls_provider_fails_naming_the_field() {
      assertThatThrownBy(() -> Nessy.harness(h -> {}))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("provider");
    }

    @Test
    void a_null_harness_customizer_is_rejected() {
      assertThatThrownBy(() -> Nessy.harness(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("customizer");
    }

    @Test
    void a_null_agent_customizer_is_rejected_on_the_string_door() {
      Harness harness = Nessy.harness(h -> h.provider(new FakeProvider("hi")));

      assertThatThrownBy(() -> harness.agent(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("customizer");
    }

    @Test
    void a_null_agent_customizer_is_rejected_on_the_typed_door() {
      Harness harness = Nessy.harness(h -> h.provider(new FakeProvider("hi")));

      assertThatThrownBy(() -> harness.agent(Greeting.class, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("customizer");
    }
  }

  @Nested
  class Harness_level_listenAsync {

    @Test
    void the_three_arg_overload_routes_a_thrown_exception_to_the_supplied_handler_without_vetoing()
        throws InterruptedException {
      FakeProvider provider = new FakeProvider("hi");
      CountDownLatch errorHandled = new CountDownLatch(1);
      List<Throwable> errors = new ArrayList<>();
      Agent<String> agent =
          Nessy.harness(
                  h ->
                      h.provider(provider)
                          .listenAsync(
                              ConversationEvent.class,
                              e -> {
                                throw new IllegalStateException("harness async listener blew up");
                              },
                              t -> {
                                errors.add(t);
                                errorHandled.countDown();
                              }))
              .agent(a -> a.name("sentinel").model("fake-model"));

      RunOutcome reply = agent.converse().tell("hi");

      assertThat(RunOutcomes.failed(reply)).isFalse();
      assertThat(errorHandled.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(errors.getFirst()).hasMessage("harness async listener blew up");
    }

    @Test
    void a_non_throwing_async_listener_never_reaches_the_error_handler_at_all()
        throws InterruptedException {
      FakeProvider provider = new FakeProvider("hi");
      CountDownLatch handled = new CountDownLatch(1);
      List<Throwable> errors = new ArrayList<>();
      Agent<String> agent =
          Nessy.harness(
                  h ->
                      h.provider(provider)
                          .listenAsync(
                              ConversationEvent.class, e -> handled.countDown(), errors::add))
              .agent(a -> a.name("sentinel").model("fake-model"));

      agent.converse().tell("hi");

      assertThat(handled.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(errors).isEmpty();
    }

    @Test
    void the_one_arg_overload_logs_to_slf4j_instead_of_requiring_a_handler_and_never_vetoes()
        throws InterruptedException {
      FakeProvider provider = new FakeProvider("hi");
      CountDownLatch handled = new CountDownLatch(1);
      Agent<String> agent =
          Nessy.harness(
                  h ->
                      h.provider(provider)
                          .listenAsync(
                              ConversationEvent.class,
                              e -> {
                                handled.countDown();
                                throw new IllegalStateException("harness async listener blew up");
                              }))
              .agent(a -> a.name("sentinel").model("fake-model"));

      RunOutcome reply = agent.converse().tell("hi");

      assertThat(RunOutcomes.failed(reply)).isFalse();
      assertThat(handled.await(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Nested
  class Parks_downgrade_warning {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;
    private Level originalLevel;

    @BeforeEach
    void wires_a_capturing_appender_onto_the_harness_builder_logger() {
      logger = (Logger) LoggerFactory.getLogger(HarnessConfig.class);
      originalLevel = logger.getLevel();
      logger.setLevel(Level.WARN);
      appender = new ListAppender<>();
      appender.start();
      logger.addAppender(appender);
    }

    @AfterEach
    void unwires_the_appender_and_restores_the_loggers_level() {
      logger.detachAppender(appender);
      logger.setLevel(originalLevel);
    }

    /**
     * Only the WARN events — the guard's own voice. The appender hears the whole logger category,
     * and another test's async listener can blow up on its executor thread AFTER its test ends,
     * landing an unrelated ERROR here mid-capture (the flake CI caught on 2026-08-15). Filtering to
     * WARN keeps every assertion about exactly the guard, immune to cross-test log bleed.
     */
    private java.util.List<ILoggingEvent> warnings() {
      return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    @Test
    void parks_defaulted_with_an_explicitly_configured_store_warns_about_the_downgrade() {
      FakeProvider provider = new FakeProvider("hi");

      Nessy.harness(h -> h.provider(provider).store(ConversationStore.inMemory()));

      assertThat(warnings()).hasSize(1);
      ILoggingEvent event = warnings().getFirst();
      assertThat(event.getLevel()).isEqualTo(Level.WARN);
      assertThat(event.getFormattedMessage()).contains("parks").contains(".parks(");
    }

    @Test
    void both_defaulted_stays_silent() {
      FakeProvider provider = new FakeProvider("hi");

      Nessy.harness(h -> h.provider(provider));

      assertThat(warnings()).isEmpty();
    }

    @Test
    void an_explicitly_configured_parks_stays_silent_even_with_a_configured_store() {
      FakeProvider provider = new FakeProvider("hi");

      Nessy.harness(
          h -> h.provider(provider).store(ConversationStore.inMemory()).parks(Parks.inMemory()));

      assertThat(warnings()).isEmpty();
    }

    @Test
    void an_explicitly_configured_parks_stays_silent_with_a_defaulted_store() {
      FakeProvider provider = new FakeProvider("hi");

      Nessy.harness(h -> h.provider(provider).parks(Parks.inMemory()));

      assertThat(warnings()).isEmpty();
    }
  }
}
