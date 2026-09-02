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
import java.time.Duration;
import java.util.Objects;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.Approver;

/**
 * An {@link Approver} that asks Open Policy Agent, so the rules are Rego rather than Java.
 *
 * <p>This is the payoff of {@link ApprovalRequest} being flat and JSON-shaped. A policy engine's
 * native input is a JSON document; OPA evaluates {@code input.toolName} and {@code
 * input.arguments.target} directly, and nothing here translates between a Java object graph and
 * what Rego can see. Cedar, AWS Verified Permissions and most of the field take the same shape, so
 * the adapter below is about forty lines whichever you pick.
 *
 * <p><b>Why rules live outside the application.</b> A gate written in Java ships when the
 * application ships. A gate written in Rego is data: it is reviewed by whoever owns the risk, it is
 * versioned on its own, and it changes without a release. That is the whole reason to reach for an
 * engine rather than an {@code if}.
 *
 * <p><b>The reply token is deliberately not sent.</b> {@link ApprovalRequest#replyToken()} is a
 * capability — whoever holds it can settle this call — and a policy engine has no business settling
 * anything. It logs its input, and it is frequently somebody else's service. So this builds the
 * document field by field rather than serializing the record, and the field that matters by its
 * absence is the one carrying authority.
 *
 * <p><b>A policy that cannot be reached denies.</b> An engine that is down, slow or misconfigured
 * must not become an open gate: the failure of a control is not permission. The reason names the
 * cause, because a person reading a denial deserves to know it came from plumbing rather than from
 * a rule.
 */
public final class OpaApprover implements Approver {

  private final HttpClient http;
  private final ObjectMapper mapper;
  private final URI decision;

  /**
   * @param baseUrl where OPA listens, e.g. {@code http://localhost:8181}
   * @param decisionPath the rule to ask, in slash form — {@code nessy/tools} asks {@code
   *     data.nessy.tools}
   */
  public OpaApprover(String baseUrl, String decisionPath, ObjectMapper mapper) {
    Objects.requireNonNull(baseUrl, "baseUrl must not be null");
    Objects.requireNonNull(decisionPath, "decisionPath must not be null");
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    this.decision =
        URI.create(
            baseUrl.replaceAll("/+$", "") + "/v1/data/" + decisionPath.replaceAll("^/+", ""));
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  }

  @Override
  public Awaited<ApprovalResult> approve(ApprovalRequest request) {
    try {
      JsonNode result = ask(asInput(request));
      // A rule that did not fire leaves `allow` undefined, which is a denial rather than a
      // silence: Rego's default answer to "may I" is no, and this preserves that.
      if (result.path("allow").asBoolean(false)) {
        return Awaited.ready(ApprovalResult.approved());
      }
      String reason = result.path("reason").asText("");
      return Awaited.ready(ApprovalResult.denied(reason.isBlank() ? "denied by policy" : reason));
    } catch (IOException | InterruptedException | RuntimeException failure) {
      if (failure instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return Awaited.ready(
          ApprovalResult.denied("the policy engine could not be reached: " + failure.getMessage()));
    }
  }

  /**
   * The document Rego reads, built field by field.
   *
   * <p>Everything a rule could reasonably judge on — who is asking, what tool, the arguments it
   * would run with, and the sentence a person would have been shown — and nothing that grants
   * authority.
   */
  ObjectNode asInput(ApprovalRequest request) {
    ObjectNode input = mapper.createObjectNode();
    input.put("agentType", request.agentType().name());
    input.put("agentId", request.agentId().value());
    input.put("turnId", request.turnId().value());
    input.put("callId", request.callId().value());
    input.put("toolName", request.toolName());
    input.put("action", request.action());
    input.put("askedAt", request.askedAt().toString());
    input.set("arguments", request.arguments());
    input.set("facts", request.facts());
    return input;
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
      throw new IllegalStateException("OPA answered " + response.statusCode());
    }
    // OPA wraps every answer in `result`. An unknown path yields `{}` — no rule, so no permission.
    return mapper.readTree(response.body()).path("result");
  }
}
