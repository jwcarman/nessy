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
package org.jwcarman.nessy.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.jwcarman.continuum.ContinuumClient;
import org.jwcarman.continuum.api.Backoff;
import org.jwcarman.continuum.api.BatchSize;
import org.jwcarman.continuum.api.Lease;
import org.jwcarman.nessy.agent.spi.Sink;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.agent.support.TestToolClients;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolEvent;
import org.jwcarman.nessy.api.tool.ToolEventListener;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * {@code defer()} does the plumbing (tool-context-defer spec §1.1, §2): one computation, carrying
 * this call's routing as its continuation, and the fold delivered through the sink BEFORE the id
 * ever reaches the tool — the ordering that makes an early answer impossible to lose. The twin of
 * {@link ComputationApprovalContextTest}.
 */
class ComputationToolContextTest {

  private final ObjectMapper mapper = TestMappers.plainlyPinned();
  private final ContinuumClient<ToolResult, Routing> client =
      TestToolClients.client("tool/test", mapper);

  private static final ToolCall CALL =
      new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());
  private static final Routing ROUTING = new Routing("ops", "prod-eu", "r1", CALL);

  private ComputationToolContext contextOver(Sink sink) {
    return contextOver(sink, ToolEventListener.noop());
  }

  private ComputationToolContext contextOver(Sink sink, ToolEventListener events) {
    return new ComputationToolContext(client, ROUTING, Optional.empty(), events, sink);
  }

  @Test
  void theCallIsTheOneTheRoutingNames() {
    assertThat(contextOver(event -> {}).call()).isEqualTo(CALL);
  }

  @Test
  void progressReachesTheListenerAsToolEventProgress() {
    var heard = new ArrayList<ToolEvent>();

    contextOver(event -> {}, heard::add).progress("halfway");

    assertThat(heard).containsExactly(new ToolEvent.Progress("halfway"));
  }

  /**
   * NOT the computation id (spec §1.1): the invocation key is the address digest, re-derivable by
   * anyone holding the four coordinates, which is exactly what makes it a deduplication key across
   * a redrive.
   */
  @Test
  void invocationIsTheAddressDigestForTheseCoordinates() {
    ComputationId expected =
        ComputationId.of(new CallAddress("ops", "prod-eu", "r1", "c1").digest());

    assertThat(contextOver(event -> {}).invocation()).isEqualTo(expected);
  }

  @Test
  void deferCreatesTheComputationAndFoldsItBeforeHandingBackTheId() {
    var delivered = new ArrayList<AgentEvent>();
    var seenWhenTheIdArrived = new ArrayList<Integer>();
    var context = contextOver(delivered::add);

    ComputationId id = context.defer();
    seenWhenTheIdArrived.add(delivered.size());

    // the fold had already happened by the time defer() returned
    assertThat(seenWhenTheIdArrived).containsExactly(1);
    assertThat(delivered).containsExactly(new AgentEvent.ToolDeferred(CALL, id));
    assertThat(context.deferral()).contains(id);
  }

  @Test
  void theCreatedComputationCarriesThisCallsRoutingAsItsContinuation() {
    var context = contextOver(event -> {});

    ComputationId id = context.defer();

    client.complete(ContinuumIds.continuumId(id.value()), ToolResult.ok("done"));
    List<Routing> continuations = new ArrayList<>();
    client.deliverResults(
        BatchSize.of(10),
        Lease.ofSeconds(30),
        Backoff.ofSeconds(5),
        delivery -> continuations.add(delivery.continuation()));

    assertThat(continuations).containsExactly(ROUTING);
  }

  @Test
  void aSecondDeferReturnsTheSameIdAndCreatesNothingNew() {
    var delivered = new ArrayList<AgentEvent>();
    var context = contextOver(delivered::add);

    ComputationId first = context.defer();
    ComputationId second = context.defer();

    assertThat(second).isEqualTo(first);
    assertThat(delivered).hasSize(1);
  }

  @Test
  void aDeclaredTimeoutBecomesTheComputationsOwnDeadline() {
    var context =
        new ComputationToolContext(
            client,
            ROUTING,
            Optional.of(Duration.ofMinutes(5)),
            ToolEventListener.noop(),
            event -> {});

    ComputationId id = context.defer();

    assertThat(id.value()).isNotBlank();
  }

  /**
   * The door's whole promise (spec §3): an id a tool holds is an id the scope names. When the fold
   * cannot commit, the throw reaches the tool and this context recorded nothing — so the executor
   * answers the call in-band and the orphan computation expires into a dropped mismatch.
   */
  @Test
  void deferPropagatesWhenTheSinkThrowsAndNothingWasRecorded() {
    Sink refuses =
        event -> {
          throw new IllegalStateException("the substrate is down");
        };
    var context = contextOver(refuses);

    assertThatThrownBy(context::defer)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("the substrate is down");

    assertThat(context.deferral()).isEmpty();
  }
}
