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
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.jwcarman.nessy.approval.policy.Verdict;

/**
 * Reads what a policy answered into a {@link Verdict}.
 *
 * <p>This exists because <b>the answer's shape is not standardized</b>. OPA passes any JSON a rule
 * produces through untouched — it has no notion of {@code allow}, {@code effect} or {@code reason}
 * — so every deployment has a convention, and the convention is the deployment's, not OPA's.
 * Verified by renaming a rule and inventing its keys; OPA was equally happy with both.
 *
 * <p>Shapes ARE dictated where something on the other end defines the wire: OPA's Envoy plugin
 * expects {@code {"allowed":…, "http_status":…}}, Gatekeeper matches Kubernetes' {@code
 * AdmissionReview}, and AuthZEN defines its own. Swapping the interpreter is how those are served
 * without touching transport or rendering.
 *
 * <p><b>An unreadable answer is not a decision.</b> Implementations THROW rather than returning a
 * denial, so {@code PolicyApprover} logs a broken gate rather than recording a policy's opinion.
 * The distinction matters: a denial means the gate worked.
 */
@FunctionalInterface
public interface DecisionInterpreter {

  /**
   * @param result the value of OPA's {@code result} key — the rule's own output
   * @throws IllegalArgumentException if this is not an answer this interpreter understands
   */
  Verdict interpret(JsonNode result);

  /**
   * Nessy's own convention: {@code {"effect": "allow" | "deny" | "delegate", …}}.
   *
   * <pre>{@code
   * {"effect": "allow"}
   * {"effect": "deny",     "reason": "never in this tenant"}
   * {"effect": "delegate", "to": "humans", "term": "PT72H"}
   * }</pre>
   *
   * <p>Every key other than {@code effect} and {@code to} rides along as a fact namespaced under
   * {@code policy.}, so a delegate reads {@code policy.term} and ignores what it does not know. An
   * effect nobody recognises throws — a policy edited into nonsense, {@code "alow"} say, must never
   * read as a yes.
   */
  static DecisionInterpreter effectStyle() {
    return result -> {
      if (result == null || !result.isObject()) {
        throw new IllegalArgumentException(
            "a decision must be an object with an \"effect\", but was: " + result);
      }
      String effect = result.path("effect").asText("");
      return switch (effect) {
        case "allow" -> Verdict.approve();
        case "deny" -> Verdict.deny(result.path("reason").asText("denied by policy"));
        case "delegate" -> {
          String to = result.path("to").asText("");
          if (to.isBlank()) {
            throw new IllegalArgumentException("a delegating decision must name a \"to\"");
          }
          yield new Verdict.Delegate(to, carried(result));
        }
        default ->
            throw new IllegalArgumentException("\"" + effect + "\" is not allow, deny or delegate");
      };
    };
  }

  /** Everything the policy attached beyond the routing itself, namespaced for the delegate. */
  private static ObjectNode carried(JsonNode result) {
    ObjectNode facts = JsonNodeFactory.instance.objectNode();
    result
        .properties()
        .forEach(
            (Map.Entry<String, JsonNode> field) -> {
              if (!field.getKey().equals("effect") && !field.getKey().equals("to")) {
                facts.set("policy." + field.getKey(), field.getValue());
              }
            });
    return facts;
  }

  /**
   * The OpenID Foundation's Authorization API 1.0 (AuthZEN): {@code {"decision": <boolean>}}.
   *
   * <p><b>It cannot express {@link Verdict.Delegate}.</b> The specification defines the decision as
   * "a boolean value that specifies whether the Decision is to allow or deny the operation", and
   * nothing more. A policy that needs to route a call to a person or a reviewer needs a richer
   * answer than AuthZEN defines — which is the argument for this seam existing rather than for
   * adopting one shape everywhere.
   *
   * <p>Its optional {@code context} carries {@code reason_user}, which is the closest thing to a
   * denial's reason, so that is what a denial reports.
   */
  String DECISION_FIELD = "decision";

  static DecisionInterpreter authzen() {
    return result -> {
      if (result == null
          || !result.hasNonNull(DECISION_FIELD)
          || !result.get(DECISION_FIELD).isBoolean()) {
        throw new IllegalArgumentException(
            "an AuthZEN response must carry a boolean \"decision\", but was: " + result);
      }
      if (result.get(DECISION_FIELD).booleanValue()) {
        return Verdict.approve();
      }
      JsonNode context = result.path("context");
      String reason = context.path("reason_user").asText("");
      return Verdict.deny(reason.isBlank() ? "denied by policy" : reason);
    };
  }
}
