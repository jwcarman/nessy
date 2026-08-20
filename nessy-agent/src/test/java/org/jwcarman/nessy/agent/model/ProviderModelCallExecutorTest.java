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
package org.jwcarman.nessy.agent.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.AgentEvent;
import org.jwcarman.nessy.agent.ModelOutcome;
import org.jwcarman.nessy.agent.memory.VerbatimMemory;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.RecordingTurnObserver;
import org.jwcarman.nessy.agent.support.ScriptedModelProvider;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ThinkingBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

class ProviderModelCallExecutorTest {

  private static final ToolCall CALL =
      new ToolCall("c1", "lookup", JsonNodeFactory.instance.objectNode());

  private ModelOutcome run(
      List<ModelEvent> script, VerbatimMemory memory, RecordingTurnObserver turn) {
    var pump = new PumpedExecutor();
    var provider = new ScriptedModelProvider(List.of(script));
    var executor =
        new ProviderModelCallExecutor(
            provider, TestSettings.settings(), TestSettings.emptyRegistry(), memory, turn, pump);
    var delivered = new ArrayList<AgentEvent>();
    executor.callModel(delivered::add);
    pump.pumpUntilQuiet();
    assertThat(delivered).hasSize(1);
    return ((AgentEvent.ModelFinished) delivered.getFirst()).outcome();
  }

  @Test
  void textChunksMergeIntoOneBlockAndNarrateAsDeltas() {
    var turn = new RecordingTurnObserver();
    var outcome =
        run(
            List.of(new ModelEvent.TextChunk("Hel"), new ModelEvent.TextChunk("lo")),
            new VerbatimMemory(),
            turn);
    assertThat(outcome)
        .isEqualTo(new ModelOutcome.Responded(List.of(new TextBlock("Hello")), List.of()));
    assertThat(turn.events())
        .contains(new TurnEvent.TextDelta("Hel"), new TurnEvent.TextDelta("lo"));
  }

  @Test
  void thinkingIsSignedAndToolUseCarriesItsSignature() {
    var turn = new RecordingTurnObserver();
    var outcome =
        run(
            List.of(
                new ModelEvent.ThinkingChunk("hmm"),
                new ModelEvent.ThinkingSigned("anthropic-sig"),
                new ModelEvent.ToolUseEmitted(CALL, "gemini-sig")),
            new VerbatimMemory(),
            turn);
    var responded = (ModelOutcome.Responded) outcome;
    assertThat(responded.content())
        .containsExactly(
            new ThinkingBlock("hmm", "anthropic-sig"), new ToolUseBlock(CALL, "gemini-sig"));
    assertThat(responded.calls()).containsExactly(CALL);
    assertThat(turn.events()).contains(new TurnEvent.ToolCallRequested(CALL));
  }

  @Test
  void theRequestCarriesTheRecalledContext() {
    var memory = new VerbatimMemory();
    memory.remember(Message.user("earlier"));
    var turn = new RecordingTurnObserver();
    var pump = new PumpedExecutor();
    var provider = new ScriptedModelProvider(List.of(List.of(new ModelEvent.TextChunk("ok"))));
    var executor =
        new ProviderModelCallExecutor(
            provider, TestSettings.settings(), TestSettings.emptyRegistry(), memory, turn, pump);
    executor.callModel(event -> {});
    pump.pumpUntilQuiet();
    assertThat(provider.requests()).hasSize(1);
    assertThat(provider.requests().getFirst().context().messages())
        .containsExactly(Message.user("earlier"));
  }

  @Test
  void aProviderExplosionDeliversAFailedOutcomeInsteadOfEscaping() {
    var turn = new RecordingTurnObserver();
    var pump = new PumpedExecutor();
    var exploding =
        new ModelProvider() {
          @Override
          public ModelStream stream(ModelRequest request) {
            throw new IllegalStateException("boom");
          }

          @Override
          public Set<Capability> capabilities() {
            return Set.of();
          }
        };
    var executor =
        new ProviderModelCallExecutor(
            exploding,
            TestSettings.settings(),
            TestSettings.emptyRegistry(),
            new VerbatimMemory(),
            turn,
            pump);
    var delivered = new ArrayList<AgentEvent>();
    executor.callModel(delivered::add);
    pump.pumpUntilQuiet();
    assertThat(delivered).hasSize(1);
    var outcome = ((AgentEvent.ModelFinished) delivered.getFirst()).outcome();
    assertThat(outcome).isInstanceOf(ModelOutcome.Failed.class);
  }
}
