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
package org.jwcarman.nessy.examples.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.Approver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An {@link Approver} that asks Open Policy Agent, so the rules are Rego rather than Java.
 *
 * <p>This is the payoff of {@link ApprovalRequest} being flat and JSON-shaped. A policy engine's
 * native input is a JSON document; OPA evaluates {@code input.toolName} and {@code
 * input.arguments.target} directly, and nothing here translates between a Java object graph and
 * what Rego can see.
 *
 * <p><b>Why rules live outside the application.</b> A gate written in Java ships when the
 * application ships. A gate written in Rego is data: reviewed by whoever owns the risk, versioned
 * on its own, and changed without a release.
 *
 * <h2>A decision is a document, not a boolean</h2>
 *
 * A boolean can only say yes or no, and the answer that matters most in this system is neither:
 * <b>ask a person</b>. So the rule returns a document, and {@code effect} carries the verdict:
 *
 * <pre>{@code
 * default decision := {"effect": "deny", "reason": "no rule allowed this"}
 *
 * decision := {"effect": "allow"} if input.toolName in {"disk_usage", "containers"}
 *
 * decision := {
 *   "effect": "ask",
 *   "reason": "targets production",
 *   "term":   "PT72H",
 * } if startswith(input.arguments.target, "prod-")
 * }</pre>
 *
 * <p>{@code ask} parks the call and waits for a human, and {@code term} is how long — so "changes
 * to production wait three days" is a sentence in the policy rather than a constant in Java.
 *
 * <h2>Your decision rule needs a default</h2>
 *
 * Give the rule a {@code default}, as above.
 *
 * <p>That is not style. OPA answers HTTP 200 to almost everything, and an undefined rule comes back
 * as {@code {}} — no {@code result} key. So does a MISTYPED DECISION PATH, and so does a policy
 * that never loaded. All three are indistinguishable from each other. With a {@code default} the
 * rule is always defined, which makes the presence of {@code result} a health check: if it is
 * missing, the policy is not answering, and that is a misconfiguration rather than a decision.
 *
 * <p>Measured, not assumed — {@code /v1/data/probe/allow} returns {@code {"result":false}} where
 * {@code /v1/data/probe/alow} returns {@code {}}, both with status 200.
 *
 * <h2>Four answers, not two</h2>
 *
 * <ul>
 *   <li>{@code result} is true — allowed.
 *   <li>{@code result} is false — the policy said no.
 *   <li>{@code result} is present but not a boolean — the rule is not a decision; denied, and said
 *       so plainly rather than coerced.
 *   <li>{@code result} is absent, or the status is not 200 — the policy is NOT ANSWERING. Denied,
 *       and logged as an error, because a control that cannot be reached is not a control that said
 *       yes, and a silent deny-everything is the failure this class exists to make loud.
 * </ul>
 */
public final class OpaApprover implements Approver {

  private static final Logger LOG = LoggerFactory.getLogger(OpaApprover.class);

  private final HttpClient http;
  private final ObjectMapper mapper;
  private final InputRenderer renderer;
  private final URI decision;
  private final Clock clock;
  private final Duration defaultTerm;

  /** How long a parked call waits when the policy asks for a person but names no term. */
  public static final Duration DEFAULT_TERM = Duration.ofDays(3);

  /** Asks {@code decisionPath} with the standard input document. */
  public OpaApprover(String baseUrl, String decisionPath, ObjectMapper mapper) {
    this(
        baseUrl,
        decisionPath,
        mapper,
        InputRenderer.standard(mapper),
        Clock.systemUTC(),
        DEFAULT_TERM);
  }

  /**
   * @param baseUrl where OPA listens, e.g. {@code http://localhost:8181}
   * @param decisionPath the RULE to ask, in slash form — {@code nessy/tools/allow} asks {@code
   *     data.nessy.tools.allow}
   * @param renderer builds the input document this policy is written against
   */
  public OpaApprover(
      String baseUrl,
      String decisionPath,
      ObjectMapper mapper,
      InputRenderer renderer,
      Clock clock,
      Duration defaultTerm) {
    Objects.requireNonNull(baseUrl, "baseUrl must not be null");
    Objects.requireNonNull(decisionPath, "decisionPath must not be null");
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.defaultTerm = Objects.requireNonNull(defaultTerm, "defaultTerm must not be null");
    this.decision =
        URI.create(
            baseUrl.replaceAll("/+$", "") + "/v1/data/" + decisionPath.replaceAll("^/+", ""));
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  }

  @Override
  public Awaited<ApprovalResult> approve(ApprovalRequest request) {
    try {
      return decide(ask(renderer.render(request)));
    } catch (InterruptedException interrupted) {
      // Restore the flag before answering: the thread is the engine's, not ours.
      Thread.currentThread().interrupt();
      return Awaited.ready(unreachable(interrupted));
    } catch (IOException | RuntimeException failure) {
      return Awaited.ready(unreachable(failure));
    }
  }

  /** What the body means, given the rule is required to carry a default. */
  private Awaited<ApprovalResult> decide(JsonNode body) {
    JsonNode result = body.get("result");
    if (result == null) {
      // Not a "no" — nothing answered. A mistyped path and an unloaded policy both land here.
      LOG.error(
          "[opa] {} returned no result: the rule is undefined, the path is wrong, or the policy is"
              + " not loaded. Denying, but this is a misconfiguration rather than a decision.",
          decision);
      return Awaited.ready(
          ApprovalResult.denied("the policy did not answer: no decision at " + decision));
    }
    String effect = result.path("effect").asText("");
    String reason = result.path("reason").asText("denied by policy");
    return switch (effect) {
      case "allow" -> Awaited.ready(ApprovalResult.approved());
      case "deny" -> Awaited.ready(ApprovalResult.denied(reason));
      // The answer a boolean cannot give: park the call and wait for a person.
      case "ask" -> Awaited.deferred(clock.instant().plus(termOf(result)));
      // An effect nobody recognises is a policy edited into nonsense -- a typo like "alow" must
      // not read as a yes. Denied, and loud, because the rule believes it decided something.
      default -> {
        LOG.error(
            "[opa] {} answered with effect \"{}\", which is not allow, deny or ask",
            decision,
            effect);
        yield Awaited.ready(
            ApprovalResult.denied(
                "the policy answered with an unknown effect: \"" + effect + "\""));
      }
    };
  }

  /** How long a person has, as the policy asked, or this approver's term if it did not say. */
  private Duration termOf(JsonNode result) {
    JsonNode term = result.get("term");
    if (term == null) {
      return defaultTerm;
    }
    try {
      return Duration.parse(term.asText());
    } catch (DateTimeParseException notADuration) {
      // Falling back rather than failing: the policy DID decide, and it decided to ask a person.
      LOG.warn(
          "[opa] {} asked for a term of \"{}\", which is not ISO-8601; using {}",
          decision,
          term.asText(),
          defaultTerm);
      return defaultTerm;
    }
  }

  /** A control that did not answer is not a control that said yes. */
  private ApprovalResult unreachable(Exception failure) {
    LOG.error("[opa] could not reach {}", decision, failure);
    return ApprovalResult.denied("the policy engine could not be reached: " + failure.getMessage());
  }

  private JsonNode ask(ObjectNode input) throws IOException, InterruptedException {
    ObjectNode body = mapper.createObjectNode();
    body.set("input", input);
    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(decision)
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      // OPA answers 200 to nearly everything, so a status here means the request itself was
      // rejected -- a malformed body is 400 with {"code","message"}.
      throw new IllegalStateException(
          "OPA answered " + response.statusCode() + ": " + body(response));
    }
    JsonNode parsed = mapper.readTree(response.body());
    JsonNode warning = parsed.get("warning");
    if (warning != null) {
      // OPA hands this over for free; the common one is a missing `input` key.
      LOG.warn("[opa] {} warned: {}", decision, warning.path("message").asText(warning.toString()));
    }
    return parsed;
  }

  private static String body(HttpResponse<String> response) {
    String text = response.body();
    return text == null || text.isBlank() ? "(no body)" : text.strip();
  }
}
