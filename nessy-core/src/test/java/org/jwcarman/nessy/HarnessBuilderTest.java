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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.ConversationEvent;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

/**
 * {@code HarnessBuilder}'s own setters — {@link HarnessBuilder#observations}, {@link
 * HarnessBuilder#mapper}, and its two {@code listenAsync} overloads — isolated from the
 * model-resolution and declared-{@code listen} stories {@code HarnessTest} already covers.
 */
class HarnessBuilderTest {

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
        Nessy.harness(provider)
            .observations(observations)
            .build()
            .agent()
            .model("fake-model")
            .build();

    agent.converse().tell("hi");

    assertThat(observations).hasObservationWithNameEqualTo("nessy.turn");
  }

  @Test
  void the_mapper_override_is_used_for_the_default_json_renderer() {
    ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    FakeProvider provider = new FakeProvider("hi");
    Agent<Greeting> agent =
        Nessy.harness(provider)
            .mapper(mapper)
            .build()
            .agent(Greeting.class)
            .model("fake-model")
            .build();

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
  class Harness_level_listenAsync {

    @Test
    void the_three_arg_overload_routes_a_thrown_exception_to_the_supplied_handler_without_vetoing()
        throws InterruptedException {
      FakeProvider provider = new FakeProvider("hi");
      CountDownLatch errorHandled = new CountDownLatch(1);
      List<Throwable> errors = new ArrayList<>();
      Agent<String> agent =
          Nessy.harness(provider)
              .listenAsync(
                  ConversationEvent.class,
                  e -> {
                    throw new IllegalStateException("harness async listener blew up");
                  },
                  t -> {
                    errors.add(t);
                    errorHandled.countDown();
                  })
              .build()
              .agent()
              .model("fake-model")
              .build();

      Reply reply = agent.converse().tell("hi");

      assertThat(reply.failed()).isFalse();
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
          Nessy.harness(provider)
              .listenAsync(ConversationEvent.class, e -> handled.countDown(), errors::add)
              .build()
              .agent()
              .model("fake-model")
              .build();

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
          Nessy.harness(provider)
              .listenAsync(
                  ConversationEvent.class,
                  e -> {
                    handled.countDown();
                    throw new IllegalStateException("harness async listener blew up");
                  })
              .build()
              .agent()
              .model("fake-model")
              .build();

      Reply reply = agent.converse().tell("hi");

      assertThat(reply.failed()).isFalse();
      assertThat(handled.await(5, TimeUnit.SECONDS)).isTrue();
    }
  }
}
