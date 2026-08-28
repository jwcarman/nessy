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
package org.jwcarman.nessy.examples.watchman.pekko;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.typesafe.config.ConfigFactory;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * Tool arguments live in {@link Claims}, not in the agent's own persisted document — see {@link
 * ToolCallRecord#argumentsClaimId()}. What matters is that the size of {@link AgentState}, once
 * serialised, does not track what a tool call carries.
 */
@DisplayName("Tool arguments the agent does not keep")
class ClaimedArgumentsTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private WatchmanActorSystem actors;

  @AfterEach
  void stop() {
    if (actors != null) {
      actors.stop();
    }
  }

  @Test
  void the_persisted_state_holds_a_claim_id_rather_than_the_arguments() {
    // A deliberately large argument: the point is that state size does not track it.
    String big = "x".repeat(200_000);
    Substrate substrate = new InMemorySubstrate(Clock.systemUTC());
    Memories memories = new Memories(substrate, 8000);
    Backlogs<String> backlogs = new SubstrateBacklogs<>(substrate, Coalescer.none(), String.class);
    actors =
        new WatchmanActorSystem(
            ConfigFactory.load("watchman-inmemory").resolve(),
            new OneBigProposal(big),
            new FakeRunner(),
            memories,
            backlogs,
            MicrometerTracing.noop(),
            Clock.systemUTC(),
            new BlockingWork(),
            Duration.ofMinutes(10),
            Duration.ofSeconds(10),
            new Claims(substrate));
    actors.start();
    String agent = "claimed-args-" + UUID.randomUUID();

    // ... run a turn whose tool is called with `big` ...
    actors.tell(agent, new AgentActor.Observe("It is noon. Do your rounds.", "rounds", Map.of()));

    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () -> assertThat(state(agent).phase()).isInstanceOf(Phase.WorkingTools.class));
    AgentState stateAfter = state(agent);

    byte[] persisted = new StateSerializer().toBinary(stateAfter);

    assertThat(new String(persisted, UTF_8)).doesNotContain(big);
    assertThat(persisted.length).isLessThan(4_000);
  }

  private AgentState state(String agent) {
    try {
      return actors.inspect(agent).toCompletableFuture().get(15, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Always proposes the one call that needs a human, carrying a huge argument -- so the round parks
   * with the record durable and nothing settles it out from under the assertion.
   */
  private record OneBigProposal(String bigArgument) implements WatchmanModel {

    @Override
    public ModelReply reply(Context context) {
      ObjectNode arguments = JSON.createObjectNode();
      arguments.put("note", bigArgument);
      ToolCall call = new ToolCall("call-prune-1", "prune_images", arguments);
      return new ModelReply.AskedForTools(
          Message.assistant(
              List.of(new TextBlock("Proposing a prune."), new ToolUseBlock(call, null))),
          List.of(call),
          Usage.zero());
    }
  }
}
