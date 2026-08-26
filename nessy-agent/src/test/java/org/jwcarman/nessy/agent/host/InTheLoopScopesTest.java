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
package org.jwcarman.nessy.agent.host;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.micrometer.observation.Observation;
import io.micrometer.observation.tck.TestObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.Harness;
import org.jwcarman.nessy.agent.memory.VerbatimMemory;
import org.jwcarman.nessy.agent.support.HarnessTeardown;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.ScriptedModel;
import org.jwcarman.nessy.agent.support.TestSettings;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.spi.Memory;
import org.jwcarman.nessy.spi.Remembrance;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

/**
 * Spans wrap work (in-the-loop amendment §1, §2): a work-scoped span opens a Micrometer SCOPE for
 * its duration, so that anything the work itself records — a JDBC statement, an HTTP call inside a
 * tool, a vendor SDK's own instrumentation — nests inside it instead of starting a trace of its
 * own.
 *
 * <p>This is the property the 2026-08-26 soak proved missing: 199 JDBC spans, 198 of them ROOTS,
 * none under the memory observations they were imported to explain. A hand-parented span cannot be
 * an ancestor of code it never ran around, and before this round none of these five spans was
 * current for one instant of the work it measured.
 *
 * <p>Each test here plants a PROBE observation inside the work — standing in for whatever
 * third-party instrumentation an application brings — and asserts on its ancestry. A probe with a
 * null parent is exactly the soak's flat span; a probe whose parent is the enclosing span is the
 * fix.
 */
class InTheLoopScopesTest {

  private static final AgentId SCOPE = AgentId.of("prod-eu");

  /** What a third-party instrumentation would record from inside the work. */
  private static final String PROBE = "probe";

  private final TestObservationRegistry registry = TestObservationRegistry.create();

  @AfterEach
  void tearDown() {
    HarnessTeardown.shutdownAllTracked();
  }

  private List<Observation.Context> contexts() {
    List<Observation.Context> captured = new ArrayList<>();
    assertThat(registry).hasHandledContextsThatSatisfy(captured::addAll);
    return captured;
  }

  /** Every probe recorded during the run, tagged by the label its planter gave it. */
  private List<Observation.Context> probes(String label) {
    return contexts().stream()
        .filter(context -> PROBE.equals(context.getName()))
        .filter(context -> label.equals(context.getContextualName()))
        .toList();
  }

  /**
   * The contextual name of the observation {@code context} was recorded inside, or {@code null}
   * when it was recorded inside nothing at all — the shape the soak found.
   */
  private static String parentNameOf(Observation.Context context) {
    var parent = context.getParentObservation();
    return parent == null ? null : parent.getContextView().getContextualName();
  }

  private void assertProbeNestsInside(String label, String expectedParent) {
    List<Observation.Context> planted = probes(label);
    assertThat(planted).as("probes labelled '%s'", label).isNotEmpty();
    assertThat(planted)
        .allSatisfy(probe -> assertThat(parentNameOf(probe)).isEqualTo(expectedParent));
  }

  /** Records one probe observation, exactly as a wrapped {@code DataSource} would. */
  private void plant(String label) {
    Observation.createNotStarted(PROBE, registry).contextualName(label).start().stop();
  }

  private Harness<String> harnessWith(
      Model model, PumpedExecutor pump, Memory memory, Tool<?> tool) {
    Harness<String> harness =
        Nessy.harness(
            h -> {
              h.model(model)
                  .systemPrompt(TestSettings.SYSTEM_PROMPT)
                  .settings(TestSettings.settings())
                  .executor(pump)
                  .observationRegistry(registry);
              if (memory != null) {
                h.memoryFactory(id -> memory);
              }
              if (tool != null) {
                h.tools(tool);
              }
            });
    HarnessTeardown.track(harness);
    return harness;
  }

  private static ScriptedModel plainAnswer() {
    return new ScriptedModel(
        List.of(
            List.of(
                new ModelEvent.TextChunk("hello back"),
                new ModelEvent.TurnEnded(StopReason.END_TURN, new Usage(5, 2, 0, 0)))));
  }

  @Nested
  class TheMemorySpans {

    /**
     * The decisive half of spec §7's second question. A {@code recall} does NOT happen inside a
     * fold — the model executor calls it on its own virtual thread, before the {@code chat} span
     * opens — so the fold's scope cannot cover it. Without a scope of its own, every store
     * statement a recall makes is a root span, which is precisely the soak's finding.
     */
    @Test
    void a_store_observation_recorded_during_a_recall_nests_inside_search_memory() {
      var pump = new PumpedExecutor();

      harnessWith(plainAnswer(), pump, new ProbingMemory("recall", "remember"), null)
          .bind(SCOPE)
          .tell("hello");
      pump.pumpUntilQuiet();

      assertProbeNestsInside("recall", "search_memory");
    }

    @Test
    void a_store_observation_recorded_during_a_remember_nests_inside_create_memory() {
      var pump = new PumpedExecutor();

      harnessWith(plainAnswer(), pump, new ProbingMemory("recall", "remember"), null)
          .bind(SCOPE)
          .tell("hello");
      pump.pumpUntilQuiet();

      assertProbeNestsInside("remember", "create_memory");
    }
  }

  @Nested
  class TheChatSpan {

    @Test
    void an_observation_recorded_while_the_model_streams_nests_inside_chat() {
      var pump = new PumpedExecutor();

      harnessWith(new ProbingModel(), pump, null, null).bind(SCOPE).tell("hello");
      pump.pumpUntilQuiet();

      assertProbeNestsInside("stream", "chat probing");
    }
  }

  @Nested
  class TheExecuteToolSpan {

    @Test
    void an_observation_recorded_inside_a_tool_body_nests_inside_execute_tool() {
      var pump = new PumpedExecutor();
      var restart = new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());
      var model =
          new ScriptedModel(
              List.of(
                  List.of(
                      new ModelEvent.ToolUseEmitted(restart, null),
                      new ModelEvent.TurnEnded(StopReason.TOOL_USE, new Usage(10, 2, 0, 0))),
                  List.of(
                      new ModelEvent.TextChunk("done"),
                      new ModelEvent.TurnEnded(StopReason.END_TURN, new Usage(20, 3, 0, 0)))));

      harnessWith(model, pump, null, new ProbingTool()).bind(SCOPE).tell("restart prod-eu");
      pump.pumpUntilQuiet();

      assertProbeNestsInside("execute", "execute_tool restart");
    }
  }

  // ------------------------------------------------------------------ the things that plant probes

  /** A {@link Memory} that records a probe from inside each operation, as a wrapped store would. */
  private final class ProbingMemory implements Memory {

    private final Memory delegate = new VerbatimMemory();
    private final String recallLabel;
    private final String rememberLabel;

    private ProbingMemory(String recallLabel, String rememberLabel) {
      this.recallLabel = Objects.requireNonNull(recallLabel);
      this.rememberLabel = Objects.requireNonNull(rememberLabel);
    }

    @Override
    public void remember(Remembrance remembrance) {
      plant(rememberLabel);
      delegate.remember(remembrance);
    }

    @Override
    public Context recall() {
      plant(recallLabel);
      return delegate.recall();
    }
  }

  /** A model that records a probe while its stream is being consumed. */
  private final class ProbingModel implements Model {

    @Override
    public ModelStream stream(ModelRequest request) {
      plant("stream");
      return new ScriptedModel(
              List.of(
                  List.of(
                      new ModelEvent.TextChunk("hello back"),
                      new ModelEvent.TurnEnded(StopReason.END_TURN, new Usage(5, 2, 0, 0)))))
          .stream(request);
    }

    @Override
    public Set<Capability> capabilities() {
      return Set.of();
    }

    @Override
    public String id() {
      return "probing";
    }

    @Override
    public String provider() {
      return "scripted";
    }
  }

  record NoInput() {}

  /** A tool that records a probe from inside its body. */
  private final class ProbingTool implements Tool<NoInput> {

    @Override
    public String name() {
      return "restart";
    }

    @Override
    public String description() {
      return "plants a probe, for the scope tests";
    }

    @Override
    public Class<NoInput> inputType() {
      return NoInput.class;
    }

    @Override
    public Awaited<ToolResult> execute(NoInput input, ToolContext context) {
      plant("execute");
      return Awaited.ready(ToolResult.ok("restarted"));
    }
  }
}
