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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.jwcarman.continuum.Continuum;
import org.jwcarman.continuum.ContinuumClient;
import org.jwcarman.continuum.DefaultContinuum;
import org.jwcarman.continuum.api.BatchSize;
import org.jwcarman.continuum.memory.InMemoryContinuumRepository;
import org.jwcarman.nessy.agent.spi.ToolExecution;
import org.jwcarman.nessy.agent.support.TestClock;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.agent.support.TestToolClients;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * {@link ComputationDeferredToolCallPolicy} in isolation, over a real {@link ContinuumClient} — the
 * tool kind's own dispatch behaviour (continuum-adoption spec §3), without a whole harness around
 * it. There is no index any more: the phase names the computation a deferred call is waiting on
 * (approval-lifecycle spec §8), so this policy only creates and reports.
 */
class ComputationDeferredToolCallPolicyTest {

  private final ObjectMapper mapper = TestMappers.plainlyPinned();
  private final TestClock clock = new TestClock(Instant.parse("2026-08-24T00:00:00Z"));
  private final Continuum continuum =
      new DefaultContinuum(new InMemoryContinuumRepository(), clock);
  private final ContinuumClient<ToolResult, Routing> toolClient =
      continuum.client(
          "tool/test",
          ToolResult.class,
          Routing.class,
          cfg ->
              cfg.resultCodec(TestToolClients.toolResultCodec(mapper))
                  .continuationCodec(Routing.codec(mapper))
                  .deadline(Duration.ofHours(1)));
  private final ComputationDeferredToolCallPolicy policy =
      new ComputationDeferredToolCallPolicy(toolClient);

  private static final ToolCall CALL =
      new ToolCall("c1", "restart_prod", JsonNodeFactory.instance.objectNode());
  private static final CallAddress ADDRESS = new CallAddress("approver", "demo", "r1", "c1");

  @Test
  void aDeferralCreatesTheComputationAndHandsBackItsId() {
    ToolExecution execution = policy.onDeferred(CALL, ADDRESS, Optional.empty());

    assertThat(execution).isInstanceOf(ToolExecution.Deferred.class);
    assertThat(((ToolExecution.Deferred) execution).computation().value()).isNotBlank();
  }

  @Test
  void everyDeferralCreatesItsOwnComputation() {
    ToolExecution first = policy.onDeferred(CALL, ADDRESS, Optional.empty());
    ToolExecution second = policy.onDeferred(CALL, ADDRESS, Optional.empty());

    assertThat(((ToolExecution.Deferred) first).computation())
        .isNotEqualTo(((ToolExecution.Deferred) second).computation());
  }

  /**
   * Proves the declared timeout, not the kind's own default deadline (1 hour here), is what got
   * stamped: advancing well past the 5-minute override but far short of the default expires the
   * computation, which only a shorter-than-default deadline explains.
   */
  @Test
  void aDeclaredTimeoutStampsAShorterDeadlineThanTheKindsDefault() {
    policy.onDeferred(CALL, ADDRESS, Optional.of(Duration.ofMinutes(5)));

    clock.advance(Duration.ofMinutes(6));
    int expired = toolClient.failExpiredComputations(BatchSize.of(10));

    assertThat(expired).isEqualTo(1);
  }
}
