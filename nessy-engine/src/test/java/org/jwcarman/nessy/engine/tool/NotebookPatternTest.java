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
package org.jwcarman.nessy.engine.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Ambient;
import org.jwcarman.nessy.api.AmbientSource;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.QueuedHarnessConfig;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;

/**
 * Tool in, background out -- the shape most things built on an agent take.
 *
 * <p>A notebook is the simplest complete example: the agent writes to it with a tool, and what it
 * wrote comes back as background on every later call. A plan, a scratchpad and a working set are
 * the same pattern with different content, which is why this is worth pinning as a whole rather
 * than as two unrelated halves.
 *
 * <p>The thing being proved is the join between them. A tool writing somewhere is easy; background
 * appearing is easy; what matters is that a note written in turn one is in front of the model in
 * turn two <em>without ever entering the story</em> -- because background is a view of the world as
 * it stands now, and a view recorded forever stops being one.
 */
class NotebookPatternTest {

  private EngineFixture engine;

  /**
   * One engine per test, and each built around the model that test needs.
   *
   * <p>The provider is a factory-level setting -- one model serves every harness an engine hands
   * out -- so a class that varies what the model asks for varies the engine, not the harness.
   */
  private void running(InferenceProvider model) {
    engine = new EngineFixture(model);
  }

  @AfterEach
  void stopEngine() {
    if (engine != null) {
      engine.close();
    }
  }

  record Note(String text) {}

  /** Somewhere to keep what the agent wrote. A real one is a table. */
  private final Map<UUID, String> notebook = new ConcurrentHashMap<>();

  /** What the model was shown as background, call by call. */
  private final ConcurrentLinkedQueue<String> systemPrompts = new ConcurrentLinkedQueue<>();

  private Tool<Note> remember(AgentId agentId) {
    return new Tool<>() {
      @Override
      public Class<Note> inputType() {
        return Note.class;
      }

      @Override
      public ToolName name() {
        return new ToolName("remember");
      }

      @Override
      public String description() {
        return "writes something down for later";
      }

      @Override
      public Awaited<ToolResult> call(ToolCallRequest<Note> request) {
        notebook.put(agentId.value(), request.input().text());
        return Awaited.ready(ToolResult.ok(new Block.Text("noted")));
      }
    };
  }

  /** The state replay produces, named -- there is no state column to read. */
  private String agentStateOf(AgentType agentType, AgentId agentId) {
    return engine.stateOf(agentType, agentId).getClass().getSimpleName();
  }

  @Test
  void whatAnAgentWritesDownIsInFrontOfItOnTheNextTurn() {
    AgentType type = new AgentType("notebook-pattern");
    AgentId agentId = new AgentId(UUID.randomUUID());

    // Writes on the first turn, and thereafter just answers. What it answers is not the point;
    // what it was shown is, and that is recorded on the way through.
    InferenceProvider model =
        (request, _) -> {
          systemPrompts.add(request.systemPrompt().value() + render(request));
          return request.context().turns().stream().anyMatch(turn -> !turn.exchanges().isEmpty())
              ? new InferenceResult.Answer(List.of(new Block.Text("noted")))
              : new InferenceResult.Actions(
                  List.of(
                      new Block.ToolCall(
                          "call_1", "remember", "{\"text\":\"the deploy is frozen\"}")));
        };

    running(model);
    QueuedHarness<String> harness =
        engine
            .harnesses()
            .create(
                type,
                String.class,
                config ->
                    config
                        .systemPrompt("You are a test assistant.")
                        .tool(remember(agentId))
                        .inference(
                            in ->
                                in.model("a-model")
                                    // The other half. Asked afresh on every call, off the agent's
                                    // row lock, so it may read whatever it keeps -- and so it can
                                    // say something different next time.
                                    .context(
                                        ctx ->
                                            ctx.ambient(
                                                AmbientSource.of(
                                                    source ->
                                                        source
                                                            .kind("notebook")
                                                            .text(
                                                                who ->
                                                                    Optional.ofNullable(
                                                                        notebook.get(
                                                                            who.value())))))))
                        .effects(e -> e.pollInterval(Duration.ofMillis(50))));

    harness.tell(agentId, "remember that the deploy is frozen");
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(agentStateOf(type, agentId)).isEqualTo("Idle"));

    assertThat(notebook).containsEntry(agentId.value(), "the deploy is frozen");
    assertThat(systemPrompts)
        .as("nothing was written down before the tool ran, so the first call saw none")
        .first()
        .asString()
        .doesNotContain("notebook");

    // A second, entirely separate turn.
    harness.tell(agentId, "what did I tell you?");
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              assertThat(agentStateOf(type, agentId)).isEqualTo("Idle");
              assertThat(systemPrompts).hasSizeGreaterThan(2);
            });

    assertThat(systemPrompts.stream().skip(2))
        .as("and every call after the note was written carries it")
        .isNotEmpty()
        .allSatisfy(
            prompt -> assertThat(prompt).contains("notebook").contains("the deploy is frozen"));
  }

  /**
   * Background never reaches the story.
   *
   * <p>The invariant that makes it background rather than an input. If it were written down it
   * would be re-sent verbatim forever, and a note about a frozen deploy would still be in front of
   * the model long after the deploy unfroze -- stated as a fact somebody said rather than as a view
   * that has since moved.
   *
   * <p>The story is the events and the turn's own content. What the model was shown is recorded by
   * reference beside the story, not in it: each model call's manifest names the ambient section as
   * a payload, stored once however many calls were made with the same text.
   */
  @Test
  void backgroundIsNeverWrittenToTheStory() {
    AgentType type = new AgentType("notebook-not-stored");
    AgentId agentId = new AgentId(UUID.randomUUID());

    running((_, _) -> new InferenceResult.Answer(List.of(new Block.Text("understood"))));
    QueuedHarness<String> harness =
        engine
            .harnesses()
            .create(
                type,
                String.class,
                config ->
                    config
                        .systemPrompt("You are a test assistant.")
                        .inference(
                            in ->
                                in.model("a-model")
                                    .context(
                                        ctx -> ctx.ambient(Ambient.text("clock", "it is Tuesday"))))
                        .effects(e -> e.pollInterval(Duration.ofMillis(50))));

    harness.tell(agentId, "hello");
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(agentStateOf(type, agentId)).isEqualTo("Idle"));

    assertThat(engine.story(type, agentId).toString())
        .as("no event carries the background: events are references and facts")
        .doesNotContain("it is Tuesday");
    assertThat(engine.history().forAgent(type, agentId).completedAfter(Optional.empty()))
        .as("the turn's input, model requests, tool results and answer do not hold it")
        .isNotEmpty()
        .allSatisfy(turn -> assertThat(turn.toString()).doesNotContain("it is Tuesday"));

    List<byte[]> holding =
        engine
            .jdbc()
            .sql(
                "SELECT hash FROM nessy_payload "
                    + "WHERE agent_id = ? AND position('it is Tuesday' in convert_from(content,'UTF8')) > 0")
            .params(agentId.value())
            .query(byte[].class)
            .list();
    assertThat(holding)
        .as("exactly one payload holds it: the ambient section the request's manifest recorded")
        .hasSize(1);
    AgentEvent.InferenceAnswered answered =
        engine.story(type, agentId).stream()
            .filter(AgentEvent.InferenceAnswered.class::isInstance)
            .map(AgentEvent.InferenceAnswered.class::cast)
            .findFirst()
            .orElseThrow();
    assertThat(answered.manifest().orElseThrow().ambient())
        .singleElement()
        .satisfies(
            section -> {
              assertThat(section.kind()).isEqualTo("clock");
              assertThat(section.content().value())
                  .as("the stored event's manifest names that very payload")
                  .isEqualTo(HexFormat.of().formatHex(holding.getFirst()));
            });

    String payloadContent =
        engine
            .jdbc()
            .sql(
                "SELECT string_agg(convert_from(content,'UTF8'), ' ') "
                    + "FROM nessy_payload WHERE agent_id = ?")
            .params(agentId.value())
            .query(String.class)
            .single();
    assertThat(payloadContent)
        .as("the kind label is in no payload: it is on the manifest, not in what was shown")
        .doesNotContain("clock");
  }

  /** Two sections under one label is a contradiction, refused where it is configured. */
  @Test
  void twoSourcesCannotOfferTheSameKind() {
    // Refused while the harness is being configured, so no turn ever runs and no model is asked.
    running(
        (_, _) -> {
          throw new AssertionError("this harness never gets as far as a turn");
        });
    var harnesses = engine.harnesses();
    Customizer<QueuedHarnessConfig<String>> twoNotebooks =
        config ->
            config
                .systemPrompt("You are a test assistant.")
                .inference(
                    in ->
                        in.context(
                            ctx ->
                                ctx.ambient(Ambient.text("notebook", "one"))
                                    .ambient(Ambient.text("notebook", "two"))));
    AgentType clashType = new AgentType("notebook-clash");
    assertThatThrownBy(() -> harnesses.create(clashType, String.class, twoNotebooks))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("notebook");
  }

  /** What the adapter would have rendered, so the assertions read against one string. */
  private static String render(InferenceRequest request) {
    return request.context().ambient().stream()
        .map(
            ambient ->
                "<"
                    + ambient.kind()
                    + ">"
                    + ambient.content().stream()
                        .map(block -> ((Block.Text) block).text())
                        .reduce("", String::concat)
                    + "</"
                    + ambient.kind()
                    + ">")
        .reduce("", String::concat);
  }
}
