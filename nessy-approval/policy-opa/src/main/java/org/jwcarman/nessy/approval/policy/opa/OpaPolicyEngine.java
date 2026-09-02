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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.approval.policy.PolicyEngine;
import org.jwcarman.nessy.approval.policy.Verdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link PolicyEngine} backed by Open Policy Agent, so the rules are Rego rather than Java.
 *
 * <pre>{@code
 * PolicyEngine opa = OpaPolicyEngine.create(opa -> opa
 *     .url("http://localhost:8181")
 *     .decisionPath("nessy/tools/decision"));
 * }</pre>
 *
 * <h2>Point it at a rule with a default</h2>
 *
 * <pre>{@code
 * default decision := {"effect": "deny", "reason": "no rule allowed this"}
 * }</pre>
 *
 * <p>That is not style, it is the only way to tell a working gate from a broken one. Measured
 * against the real binary: OPA answers <b>HTTP 200 to nearly everything</b>, and an undefined rule
 * comes back as {@code {}} — no {@code result} key. So does a MISTYPED DECISION PATH, and so does a
 * policy that never loaded. All three are byte-identical.
 *
 * <pre>
 * /v1/data/probe/allow  ->  {"result":false}   200
 * /v1/data/probe/alow   ->  {}                 200   (a typo)
 * </pre>
 *
 * <p>Read {@code {}} as "no" and a misconfigured path denies everything, forever, with nothing in
 * any log. With a {@code default} the rule is always defined, so the PRESENCE of {@code result}
 * becomes the health check — and its absence is reported as a broken gate rather than served as a
 * decision.
 *
 * <p><b>Not answering is signalled by throwing.</b> A verdict returned from here means the policy
 * decided something; an exception means it did not. {@code PolicyApprover} turns the second into a
 * denial and an error in the log, which is the distinction that matters when somebody asks at 3am
 * whether the gate is working or just saying no.
 */
public final class OpaPolicyEngine implements PolicyEngine {

  private static final Logger LOG = LoggerFactory.getLogger(OpaPolicyEngine.class);

  private final HttpClient http;
  private final ObjectMapper mapper;
  private final InputRenderer renderer;
  private final DecisionInterpreter interpreter;
  private final URI decision;
  private final Duration timeout;

  private OpaPolicyEngine(Configured configured) {
    this.mapper = configured.mapper;
    this.renderer =
        configured.renderer != null
            ? configured.renderer
            : InputRenderer.standard(configured.mapper);
    this.interpreter =
        configured.interpreter != null ? configured.interpreter : DecisionInterpreter.effectStyle();
    this.decision =
        URI.create(
            withoutTrailingSlashes(configured.url)
                + "/v1/data/"
                + withoutLeadingSlashes(configured.decisionPath));
    this.timeout = configured.timeout;
    this.http = HttpClient.newBuilder().connectTimeout(configured.connectTimeout).build();
  }

  public static OpaPolicyEngine create(Consumer<OpaPolicyEngineConfig> customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    Configured configured = new Configured();
    customizer.accept(configured);
    if (configured.url == null) {
      throw new IllegalStateException("an OPA engine needs a url: call url(...)");
    }
    if (configured.decisionPath == null) {
      throw new IllegalStateException(
          "an OPA engine needs a decision path: call decisionPath(...), e.g."
              + " \"nessy/tools/decision\"");
    }
    return new OpaPolicyEngine(configured);
  }

  /**
   * Trims slashes without a regular expression.
   *
   * <p>This was {@code replaceAll("/+$", "")}, which backtracks: a greedy {@code +} anchored at the
   * end makes the engine retry from every position, so the cost is super-linear in the number of
   * trailing slashes. Nobody writes a URL like that on purpose, but a configuration value is
   * somebody else's input and this is a two-line problem either way.
   */
  private static String withoutTrailingSlashes(String url) {
    int end = url.length();
    while (end > 0 && url.charAt(end - 1) == '/') {
      end--;
    }
    return url.substring(0, end);
  }

  private static String withoutLeadingSlashes(String path) {
    int start = 0;
    while (start < path.length() && path.charAt(start) == '/') {
      start++;
    }
    return path.substring(start);
  }

  /** Where this engine will send its questions — useful in a log line or a health page. */
  public URI decisionUri() {
    return decision;
  }

  @Override
  public Verdict decide(ApprovalRequest request) {
    JsonNode body = ask(renderer.render(request));
    JsonNode result = body.get("result");
    if (result == null) {
      throw new IllegalStateException(
          "no decision at "
              + decision
              + ": the rule is undefined, the path is wrong, or the policy is not loaded");
    }
    return interpreter.interpret(result);
  }

  private JsonNode ask(ObjectNode input) {
    ObjectNode body = mapper.createObjectNode();
    body.set("input", input);
    HttpResponse<String> response;
    try {
      response =
          http.send(
              HttpRequest.newBuilder(decision)
                  .timeout(timeout)
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException interrupted) {
      // Restore the flag before unwinding: the thread is the engine's, not ours.
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted asking " + decision, interrupted);
    } catch (IOException unreachable) {
      throw new IllegalStateException("could not reach " + decision, unreachable);
    }
    if (response.statusCode() != 200) {
      // OPA answers 200 to nearly everything, so a status here means the REQUEST was rejected --
      // a malformed body is 400 with {"code","message"}.
      throw new IllegalStateException(
          "OPA answered " + response.statusCode() + " from " + decision + ": " + response.body());
    }
    try {
      JsonNode parsed = mapper.readTree(response.body());
      JsonNode warning = parsed.get("warning");
      if (warning != null) {
        // OPA hands this over for free; the common one is a missing `input` key.
        LOG.warn(
            "[opa] {} warned: {}", decision, warning.path("message").asText(warning.toString()));
      }
      return parsed;
    } catch (IOException notJson) {
      throw new IllegalStateException("OPA answered something that is not JSON", notJson);
    }
  }

  /** Collects what {@link #create} was told. */
  private static final class Configured implements OpaPolicyEngineConfig {

    private String url;
    private String decisionPath;
    private ObjectMapper mapper = new ObjectMapper();
    private InputRenderer renderer;
    private DecisionInterpreter interpreter;
    private Duration timeout = Duration.ofSeconds(5);
    private Duration connectTimeout = Duration.ofSeconds(2);

    @Override
    public OpaPolicyEngineConfig url(String url) {
      this.url = Objects.requireNonNull(url, "url must not be null");
      return this;
    }

    @Override
    public OpaPolicyEngineConfig decisionPath(String decisionPath) {
      this.decisionPath = Objects.requireNonNull(decisionPath, "decisionPath must not be null");
      return this;
    }

    @Override
    public OpaPolicyEngineConfig objectMapper(ObjectMapper mapper) {
      this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
      return this;
    }

    @Override
    public OpaPolicyEngineConfig renderer(InputRenderer renderer) {
      this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
      return this;
    }

    @Override
    public OpaPolicyEngineConfig interpreter(DecisionInterpreter interpreter) {
      this.interpreter = Objects.requireNonNull(interpreter, "interpreter must not be null");
      return this;
    }

    @Override
    public OpaPolicyEngineConfig timeout(Duration timeout) {
      this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
      return this;
    }

    @Override
    public OpaPolicyEngineConfig connectTimeout(Duration connectTimeout) {
      this.connectTimeout =
          Objects.requireNonNull(connectTimeout, "connectTimeout must not be null");
      return this;
    }
  }
}
