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
package org.jwcarman.nessy.agent.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.api.tool.RetrySemantics;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.durable.Continuation;

/**
 * The {@code SCOPE_RESUME} routing shape: it persists inside two document kinds (the computation's
 * return address, the delivery's destination), and both the {@code computation} and {@code
 * approval} doors read the call back out of it at delivery time (spec §5a) — so its wire shape is
 * pinned exactly, not just round-tripped.
 */
class ScopeRoutingTest {

  private static final ToolCall CALL = new ToolCall("c1", "restart_prod", callArguments());

  private static ObjectNode callArguments() {
    ObjectNode arguments = JsonNodeFactory.instance.objectNode();
    arguments.put("target", "prod-eu");
    return arguments;
  }

  @Nested
  class RoundTrip {

    @Test
    void continuationForThenDecodeRecoversTheAgentCoordinateAndTheCall() {
      Continuation continuation =
          ScopeRouting.continuationFor(TestMappers.plainlyPinned(), "ops", "prod-eu", "r1", CALL);

      ScopeRouting.Routing routing = ScopeRouting.decode(TestMappers.plainlyPinned(), continuation);

      assertThat(routing.agentType()).isEqualTo("ops");
      assertThat(routing.agentId()).isEqualTo("prod-eu");
      assertThat(routing.responseId()).isEqualTo("r1");
      assertThat(routing.call()).isEqualTo(CALL);
    }

    @Test
    void theContinuationCarriesTheScopeResumeType() {
      Continuation continuation =
          ScopeRouting.continuationFor(TestMappers.plainlyPinned(), "ops", "prod-eu", "r1", CALL);

      assertThat(continuation.type()).isEqualTo("SCOPE_RESUME");
    }

    @Test
    void aMissingRetrySemanticsDecodesAsNonRetryable() {
      Continuation continuation =
          ScopeRouting.continuationFor(TestMappers.plainlyPinned(), "ops", "prod-eu", "r1", CALL);

      ScopeRouting.Routing routing = ScopeRouting.decode(TestMappers.plainlyPinned(), continuation);

      assertThat(routing.retrySemantics()).isEqualTo(RetrySemantics.NON_RETRYABLE);
      assertThat(routing.timeout()).isEmpty();
    }

    @Test
    void theToolDoorsShapeCarriesRetrySemanticsAndTimeout() {
      Continuation continuation =
          ScopeRouting.continuationFor(
              TestMappers.plainlyPinned(),
              "ops",
              "prod-eu",
              "r1",
              CALL,
              RetrySemantics.RETRYABLE,
              Optional.of(Duration.ofMinutes(5)));

      ScopeRouting.Routing routing = ScopeRouting.decode(TestMappers.plainlyPinned(), continuation);

      assertThat(routing.retrySemantics()).isEqualTo(RetrySemantics.RETRYABLE);
      assertThat(routing.timeout()).contains(Duration.ofMinutes(5));
    }
  }

  @Nested
  class GoldenShape {

    @Test
    void theDataPayloadIsTheExactPinnedJsonShape() {
      Continuation continuation =
          ScopeRouting.continuationFor(TestMappers.plainlyPinned(), "ops", "prod-eu", "r1", CALL);

      assertThat(continuation.data())
          .isEqualTo(
              "{\"agentType\":\"ops\",\"agentId\":\"prod-eu\",\"responseId\":\"r1\","
                  + "\"call\":{\"id\":\"c1\",\"name\":\"restart_prod\","
                  + "\"arguments\":{\"target\":\"prod-eu\"}}}");
    }
  }

  @Nested
  class MalformedInput {

    @Test
    void undecodableJsonFailsLoudly() {
      var continuation = new Continuation("SCOPE_RESUME", "not json");

      assertThatThrownBy(() -> ScopeRouting.decode(TestMappers.plainlyPinned(), continuation))
          .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aMissingCallFailsLoudly() {
      var continuation =
          new Continuation(
              "SCOPE_RESUME", "{\"agentType\":\"a\",\"agentId\":\"b\",\"responseId\":\"r1\"}");

      assertThatThrownBy(() -> ScopeRouting.decode(TestMappers.plainlyPinned(), continuation))
          .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aMissingResponseIdFailsLoudly() {
      var continuation =
          new Continuation(
              "SCOPE_RESUME",
              "{\"agentType\":\"a\",\"agentId\":\"b\",\"call\":{\"id\":\"c1\",\"name\":\"n\","
                  + "\"arguments\":{}}}");

      assertThatThrownBy(() -> ScopeRouting.decode(TestMappers.plainlyPinned(), continuation))
          .isInstanceOf(IllegalStateException.class);
    }
  }
}
