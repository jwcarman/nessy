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
package org.jwcarman.nessy.approval.policy.opa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.approval.policy.PolicyEngine;
import org.jwcarman.nessy.approval.policy.Verdict;

/**
 * Every way the wire can let this engine down.
 *
 * <p>A real OPA is well behaved, which is exactly why these paths need a server that is NOT. The
 * JDK's own {@link HttpServer} answers however a test tells it to, in-process, so the failure modes
 * of a security control are covered by the DEFAULT build rather than only when Docker is present —
 * which is the build that matters, because these are the paths that decide whether a broken gate
 * reads as an open one.
 */
@DisplayName("An OPA engine meeting a server that misbehaves")
class OpaPolicyEngineFailureTest {

  private HttpServer server;
  private final AtomicReference<int[]> status = new AtomicReference<>(new int[] {200});
  private final AtomicReference<String> body = new AtomicReference<>("{\"result\":{}}");

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          byte[] out = body.get().getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status.get()[0], out.length);
          exchange.getResponseBody().write(out);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private PolicyEngine engine() {
    return OpaPolicyEngine.create(
        opa ->
            opa.url("http://127.0.0.1:" + server.getAddress().getPort())
                .decisionPath("nessy/tools/decision")
                .timeout(Duration.ofSeconds(2))
                .connectTimeout(Duration.ofSeconds(1)));
  }

  private static ApprovalRequest asking() {
    return new ApprovalRequest(
        AgentType.of("watchman"),
        AgentId.of("house-12"),
        TurnId.of("turn-1"),
        CallId.of("call-1"),
        "prune_images",
        JsonNodeFactory.instance.objectNode(),
        "docker image prune -af",
        Instant.parse("2026-09-02T12:00:00Z"),
        () -> ReplyToken.of("a-capability"),
        JsonNodeFactory.instance.objectNode());
  }

  @Test
  @DisplayName("a non-200 names the status, because OPA answers 200 to nearly everything")
  void a_rejected_request_throws() {
    status.set(new int[] {400});
    body.set("{\"code\":\"invalid_parameter\",\"message\":\"body contains malformed input\"}");
    PolicyEngine engine = engine();
    ApprovalRequest asking = asking();

    assertThatThrownBy(() -> engine.decide(asking))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("OPA answered 400")
        .hasMessageContaining("invalid_parameter");
  }

  @Test
  void a_body_that_is_not_json_throws() {
    body.set("<html>502 Bad Gateway</html>");
    PolicyEngine engine = engine();
    ApprovalRequest asking = asking();

    assertThatThrownBy(() -> engine.decide(asking))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not JSON");
  }

  @Test
  @DisplayName("a response with no result is a broken gate, not a denial")
  void an_empty_document_throws() {
    body.set("{}");
    PolicyEngine engine = engine();
    ApprovalRequest asking = asking();

    assertThatThrownBy(() -> engine.decide(asking))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no decision at");
  }

  @Test
  @DisplayName("OPA's own warning is surfaced, and the decision still stands")
  void a_warning_does_not_stop_a_decision() {
    // What OPA returns when the request carried no "input" key at all.
    body.set(
        "{\"result\":{\"effect\":\"allow\"},"
            + "\"warning\":{\"code\":\"api_usage_warning\",\"message\":\"'input' key missing\"}}");

    assertThat(engine().decide(asking())).isEqualTo(Verdict.approve());
  }

  @Test
  @DisplayName("an interrupted decision restores the flag rather than swallowing it")
  void interrupting_the_caller_is_reported_and_the_flag_survives() {
    PolicyEngine engine = engine();
    ApprovalRequest asking = asking();
    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> engine.decide(asking))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("interrupted");
      assertThat(Thread.currentThread().isInterrupted())
          .as("the thread belongs to the engine, so its interrupt must not be eaten")
          .isTrue();
    } finally {
      // Clear it, or every test after this one inherits an interrupted thread.
      Thread.interrupted();
    }
  }

  @Test
  @DisplayName("a supplied interpreter is what reads the answer")
  void the_interpreter_can_be_replaced() {
    body.set("{\"result\":{\"decision\":false,\"context\":{\"reason_user\":\"out of hours\"}}}");
    PolicyEngine authzen =
        OpaPolicyEngine.create(
            opa ->
                opa.url("http://127.0.0.1:" + server.getAddress().getPort())
                    .decisionPath("nessy/tools/decision")
                    .renderer(
                        InputRenderer.authzen(new com.fasterxml.jackson.databind.ObjectMapper()))
                    .interpreter(DecisionInterpreter.authzen()));

    assertThat(authzen.decide(asking())).isEqualTo(Verdict.deny("out of hours"));
  }

  @Test
  void the_decision_uri_is_reported_for_a_log_line_or_a_health_page() {
    OpaPolicyEngine engine =
        OpaPolicyEngine.create(
            opa ->
                opa.url("http://127.0.0.1:" + server.getAddress().getPort())
                    .decisionPath("nessy/tools/decision"));

    assertThat(engine.decisionUri().toString())
        .endsWith("/v1/data/nessy/tools/decision")
        .startsWith("http://127.0.0.1:");
  }

  @Test
  @DisplayName("more than one trailing or leading slash is trimmed, not just the first")
  void multiple_slashes_are_all_trimmed() {
    OpaPolicyEngine engine =
        OpaPolicyEngine.create(
            opa ->
                opa.url("http://127.0.0.1:" + server.getAddress().getPort() + "///")
                    .decisionPath("///nessy/tools/decision"));

    assertThat(engine.decisionUri().toString())
        .isEqualTo(
            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/data/nessy/tools/decision");
  }

  @Test
  @DisplayName("an engine missing its url or its decision path is refused at construction")
  void an_incomplete_engine_is_refused() {
    assertThatThrownBy(() -> OpaPolicyEngine.create(opa -> opa.decisionPath("a/b")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("needs a url");
    assertThatThrownBy(() -> OpaPolicyEngine.create(opa -> opa.url("http://localhost:8181")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("needs a decision path");
  }
}
