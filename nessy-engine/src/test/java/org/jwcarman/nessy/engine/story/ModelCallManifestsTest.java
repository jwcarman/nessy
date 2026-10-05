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
package org.jwcarman.nessy.engine.story;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Ambient;
import org.jwcarman.nessy.api.AmbientSource;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.RetryPolicy;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.event.RequestManifest;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.inference.Failure;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;

/**
 * What each model call of a real turn stores about its request, read back from the stored story: a
 * retried call, a call for a tool and the answer that follows it.
 */
@Tag("container")
class ModelCallManifestsTest {

  private static final String PROMPT = "You are a test assistant.";

  private final List<InferenceRequest> received = new CopyOnWriteArrayList<>();

  /** Busy on its first call, asks for the lookup on its second, answers on its third. */
  private InferenceProvider busyThenCallsThenAnswers() {
    AtomicBoolean failed = new AtomicBoolean();
    return (request, narrator) -> {
      received.add(request);
      if (failed.compareAndSet(false, true)) {
        return new InferenceResult.Fault(new Failure.Transient("busy"), Usage.of("a-model", 5, 0));
      }
      boolean toolHasRun =
          request.context().turns().stream().anyMatch(turn -> !turn.exchanges().isEmpty());
      return toolHasRun
          ? new InferenceResult.Answer(
              List.of(new Block.Text("It is 1412 metres deep.")), Usage.of("a-model", 40, 9))
          : new InferenceResult.Actions(
              List.of(new Block.ToolCall("call_1", "lookup", "{\"q\":\"loch ness\"}")),
              Usage.of("a-model", 25, 6));
    };
  }

  /** Says "call 1", "call 2", "call 3" on the way into each model call. */
  private static AmbientSource ambientThatDiffersEveryCall() {
    AtomicInteger asked = new AtomicInteger();
    return AmbientSource.of(
        source ->
            source
                .kind("clock")
                .offering(
                    _ -> Optional.of(Ambient.text("clock", "call " + asked.incrementAndGet()))));
  }

  private static RequestManifest manifestOf(AgentEvent event) {
    return switch (event) {
      case AgentEvent.InferenceAttempted attempted -> attempted.request().orElseThrow();
      case AgentEvent.ActionsRequested requested -> requested.manifest().orElseThrow();
      case AgentEvent.InferenceAnswered answered -> answered.request().orElseThrow();
      default -> throw new AssertionError("not a model-call event: " + event);
    };
  }

  @Test
  void every_model_call_of_a_turn_is_stored_with_what_its_request_was_made_of() {
    AgentType type = new AgentType("manifest-queued");
    AgentId agent = AgentId.random();

    try (EngineFixture engine = new EngineFixture(busyThenCallsThenAnswers())) {
      engine
          .harnesses()
          .<String>create(
              type,
              String.class,
              config ->
                  config
                      .systemPrompt(PROMPT)
                      .ambient(ambientThatDiffersEveryCall())
                      .tool(
                          StoryTurn.lookup(),
                          t ->
                              t.action(query -> "looked up " + query.q())
                                  .approver(StoryTurn.decidesAsCarol()))
                      .inference(
                          in ->
                              in.model("a-model")
                                  .retryPolicy(
                                      new RetryPolicy.FixedDelay(
                                          2, Duration.ofMillis(100), Duration.ZERO)))
                      .effects(e -> e.pollInterval(Duration.ofMillis(50))))
          .tell(agent, "how deep is Loch Ness?");
      await()
          .atMost(Duration.ofSeconds(30))
          .until(
              () ->
                  engine.story(type, agent).stream()
                      .anyMatch(AgentEvent.InferenceAnswered.class::isInstance));

      List<AgentEvent> modelCalls =
          engine.story(type, agent).stream()
              .filter(
                  event ->
                      event instanceof AgentEvent.InferenceAttempted
                          || event instanceof AgentEvent.ActionsRequested
                          || event instanceof AgentEvent.InferenceAnswered)
              .toList();
      assertThat(modelCalls)
          .extracting(event -> event.getClass().getSimpleName())
          .containsExactly("InferenceAttempted", "ActionsRequested", "InferenceAnswered");
      assertThat(received).as("one request for each of the three calls").hasSize(3);

      List<RequestManifest> manifests =
          modelCalls.stream().map(ModelCallManifestsTest::manifestOf).toList();
      for (RequestManifest manifest : manifests) {
        assertThat(engine.content(agent, manifest.instructions()))
            .as("the instructions reference resolves to the system prompt")
            .contains(new Block.Text(PROMPT));
        assertThat(
                engine
                    .payloads()
                    .forAgent(agent)
                    .getDocument(manifest.options())
                    .get("model")
                    .asString())
            .as("the options document names the model")
            .isEqualTo("a-model");
      }
      for (int i = 0; i < manifests.size(); i++) {
        List<Ambient> sent = received.get(i).context().ambient();
        assertThat(sent).as("call %d was shown its ambient section", i + 1).hasSize(1);
        assertThat(manifests.get(i).ambient()).hasSize(1);
        assertThat(engine.content(agent, manifests.get(i).ambient().getFirst().content()))
            .as("call %d's manifest resolves to what call %d was shown", i + 1, i + 1)
            .isEqualTo(List.copyOf(sent.getFirst().content()))
            .isEqualTo(List.of(new Block.Text("call " + (i + 1))));
      }
      assertThat(manifests)
          .extracting(RequestManifest::tools)
          .as("the toolset and the choice did not change, so the reference did not")
          .containsOnly(manifests.getFirst().tools());
      PayloadRef toolsDocument = manifests.getFirst().tools();
      assertThat(engine.payloads().forAgent(agent).getDocument(toolsDocument).get("offers"))
          .as("the offers are stored where the reference says")
          .isNotNull();
    }
  }
}
