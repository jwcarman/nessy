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
package org.jwcarman.nessy.approval.policy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.Approver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An {@link Approver} that asks a {@link PolicyEngine} and carries out the {@link Verdict}.
 *
 * <p>Engine-agnostic on purpose: no HTTP, no wire format, no vendor. Everything specific to how a
 * decision is asked for and read lives behind {@link PolicyEngine}.
 *
 * <h2>Every failure denies</h2>
 *
 * A control that did not answer is not a control that said yes. The engine throwing, a name that
 * resolves to nothing, a chain that will not terminate — each is a denial AND an error in the log.
 * The log level carries the distinction that matters at 3am: <b>{@code error} means the gate is
 * broken; a denial alone means the gate worked.</b> Denying a misconfiguration quietly is how a
 * policy that was never consulted looks healthy for a year.
 */
public final class PolicyApprover implements Approver {

  private static final Logger LOG = LoggerFactory.getLogger(PolicyApprover.class);

  /**
   * How deep a chain of delegations may go before it is treated as a loop.
   *
   * <p>A delegates to B, B delegates to A. Nothing in the vocabulary forbids it, so something has
   * to notice.
   */
  public static final int DEFAULT_MAX_DEPTH = 3;

  /**
   * Namespaced, and on the request rather than in a field or a thread-local, because the depth has
   * to travel with the question: a delegate may itself be a {@code PolicyApprover}, and the count
   * only means anything if the next one can see it.
   */
  static final String DEPTH = "policy.depth";

  private final PolicyEngine engine;
  private final Map<String, Approver> delegates;
  private final int maxDepth;

  /**
   * Builds one, the way the rest of Nessy builds things.
   *
   * <pre>{@code
   * Approver gate = PolicyApprover.create(policy -> policy
   *     .engine(opa)
   *     .delegate("humans", desk));
   * }</pre>
   */
  public static PolicyApprover create(Consumer<PolicyApproverConfig> customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    Configured configured = new Configured();
    customizer.accept(configured);
    if (configured.engine == null) {
      throw new IllegalStateException("a policy approver needs an engine: call engine(...)");
    }
    return new PolicyApprover(configured.engine, configured.delegates, configured.maxDepth);
  }

  /** Collects what {@link #create} was told, and refuses a name twice. */
  private static final class Configured implements PolicyApproverConfig {

    private PolicyEngine engine;
    private final Map<String, Approver> delegates = new LinkedHashMap<>();
    private int maxDepth = DEFAULT_MAX_DEPTH;

    @Override
    public PolicyApproverConfig engine(PolicyEngine engine) {
      this.engine = Objects.requireNonNull(engine, "engine must not be null");
      return this;
    }

    @Override
    public PolicyApproverConfig delegate(String name, Approver approver) {
      Objects.requireNonNull(name, "name must not be null");
      Objects.requireNonNull(approver, "approver must not be null");
      // Refused rather than overwritten: replacing a strict reviewer with a lenient one by
      // accident is the kind of change that leaves no trace and weakens a gate silently.
      if (delegates.putIfAbsent(name, approver) != null) {
        throw new IllegalArgumentException("an approver is already registered as \"" + name + "\"");
      }
      return this;
    }

    @Override
    public PolicyApproverConfig maxDepth(int maxDepth) {
      this.maxDepth = maxDepth;
      return this;
    }
  }

  public PolicyApprover(PolicyEngine engine, Map<String, Approver> delegates) {
    this(engine, delegates, DEFAULT_MAX_DEPTH);
  }

  /**
   * @param delegates the ONLY approvers a policy may name. An allowlist rather than a lookup: given
   *     a registry of everything, a policy could name an approver that always says yes, and the
   *     gate would be one edit to a policy file away from being no gate at all.
   */
  public PolicyApprover(PolicyEngine engine, Map<String, Approver> delegates, int maxDepth) {
    this.engine = Objects.requireNonNull(engine, "engine must not be null");
    this.delegates = Map.copyOf(new LinkedHashMap<>(requireNames(delegates)));
    if (maxDepth < 1) {
      throw new IllegalArgumentException("maxDepth must be at least 1, was " + maxDepth);
    }
    this.maxDepth = maxDepth;
  }

  private static Map<String, Approver> requireNames(Map<String, Approver> delegates) {
    Objects.requireNonNull(delegates, "delegates must not be null");
    delegates.forEach(
        (name, approver) -> {
          Objects.requireNonNull(name, "a delegate name must not be null");
          Objects.requireNonNull(approver, "the approver named " + name + " must not be null");
        });
    return delegates;
  }

  @Override
  public Awaited<ApprovalResult> approve(ApprovalRequest request) {
    Verdict verdict;
    try {
      verdict = engine.decide(request);
    } catch (RuntimeException broken) {
      LOG.error("[policy] the engine could not decide; denying", broken);
      return denied("the policy engine could not decide: " + broken.getMessage());
    }
    if (verdict == null) {
      LOG.error("[policy] the engine returned no verdict at all; denying");
      return denied("the policy engine returned no verdict");
    }
    return switch (verdict) {
      case Verdict.Approve() -> Awaited.ready(ApprovalResult.approved());
      case Verdict.Deny(var reason) -> Awaited.ready(ApprovalResult.denied(reason));
      case Verdict.Delegate delegate -> handOff(request, delegate);
    };
  }

  private Awaited<ApprovalResult> handOff(ApprovalRequest request, Verdict.Delegate delegate) {
    Approver approver = delegates.get(delegate.to());
    if (approver == null) {
      // Naming something that is not on the allowlist is a policy pointing at a gate that does not
      // exist. That is a broken deployment, not a decision anybody made.
      LOG.error(
          "[policy] no approver is registered as \"{}\"; the allowlist is {}. Denying.",
          delegate.to(),
          delegates.keySet());
      return denied("the policy delegated to \"" + delegate.to() + "\", which is not registered");
    }
    int depth = depthOf(request) + 1;
    if (depth > maxDepth) {
      LOG.error(
          "[policy] delegation reached depth {} (max {}) at \"{}\"; treating it as a loop. Denying.",
          depth,
          maxDepth,
          delegate.to());
      return denied("delegation went more than " + maxDepth + " deep, which is a loop");
    }
    request.fact(DEPTH, JsonNodeFactory.instance.numberNode(depth));
    // Whatever the policy attached -- a term, a ticket -- so the delegate reads what it knows.
    delegate.facts().fields().forEachRemaining(f -> request.fact(f.getKey(), f.getValue()));
    try {
      Awaited<ApprovalResult> answer = approver.approve(request);
      if (answer == null) {
        LOG.error("[policy] the approver named \"{}\" answered null; denying", delegate.to());
        return denied("the approver \"" + delegate.to() + "\" gave no answer");
      }
      return answer;
    } catch (RuntimeException broken) {
      LOG.error("[policy] the approver named \"{}\" failed; denying", delegate.to(), broken);
      return denied("the approver \"" + delegate.to() + "\" failed: " + broken.getMessage());
    }
  }

  private static int depthOf(ApprovalRequest request) {
    return request.fact(DEPTH).map(node -> node.asInt(0)).orElse(0);
  }

  private static Awaited<ApprovalResult> denied(String reason) {
    return Awaited.ready(ApprovalResult.denied(reason));
  }
}
