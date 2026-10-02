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
package org.jwcarman.nessy.engine.tool;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.JsonSchema;
import org.jwcarman.nessy.api.RetryPolicy;
import org.jwcarman.nessy.api.Stringifier;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.tool.ApprovalEnricher;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.inference.ToolOffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One tool bound to one harness: the tool itself, the application's terms for calling it, and the
 * two things that translate between the model's words and the tool's types.
 *
 * <p><b>A binding rather than a tool.</b> The same {@code Tool} can be offered by two agent types
 * on different terms -- thirty seconds here and five there, retried in one and never in the other
 * -- because none of that is the tool's to say. What a tool knows is its name, its shape and how to
 * run; what a harness knows is what a call of it is worth. This is where the second meets the
 * first, and it is created once per {@code .tool(...)} on one harness.
 *
 * <p><b>{@code <I>} ends here.</b> Everything on the far side -- effects, entries, outcomes --
 * speaks in blocks and strings, exactly as {@code <I>} ends at the input renderer. That is what
 * lets one effect table hold calls to tools with unrelated input types.
 *
 * <p>The schema is generated once, when the tool is bound, rather than per call. A tool's shape
 * cannot change between calls, and generating it per call would put a reflective walk of the input
 * type on the path of every inference.
 */
public final class ToolBinding<I> {

  private static final Logger LOG = LoggerFactory.getLogger(ToolBinding.class);

  private static final String UNREADABLE = " (its arguments could not be read)";

  private final Tool<I> tool;
  private final ObjectMapper mapper;
  private final JsonSchema schema;
  private final Duration timeout;
  private final RetryPolicy retryPolicy;
  private final Stringifier<I> action;
  private final Stringifier<ToolResult.Success> result;
  private final List<ApprovalEnricher> enrichers;
  private final Approver approver;
  private final Duration approvalTimeout;
  private final RetryPolicy approvalRetryPolicy;

  public ToolBinding(
      Tool<I> tool,
      ObjectMapper mapper,
      JsonSchema schema,
      Duration timeout,
      RetryPolicy retryPolicy,
      Stringifier<I> action,
      Stringifier<ToolResult.Success> result,
      List<ApprovalEnricher> enrichers,
      Approver approver,
      Duration approvalTimeout,
      RetryPolicy approvalRetryPolicy) {
    this.tool = Objects.requireNonNull(tool, "tool must not be null");
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    this.schema = requireObjectAtRoot(tool, mapper, schema);
    this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
    this.retryPolicy = Objects.requireNonNull(retryPolicy, "retry policy must not be null");
    this.action = Objects.requireNonNull(action, "action stringifier must not be null");
    this.result = Objects.requireNonNull(result, "result stringifier must not be null");
    this.enrichers = List.copyOf(Objects.requireNonNull(enrichers, "enrichers must not be null"));
    this.approver = Objects.requireNonNull(approver, "approver must not be null");
    this.approvalTimeout =
        Objects.requireNonNull(approvalTimeout, "approval timeout must not be null");
    this.approvalRetryPolicy =
        Objects.requireNonNull(approvalRetryPolicy, "approval retry policy must not be null");
  }

  /**
   * Every vendor requires tool parameters to be an object, and refuses a union at the root even
   * when it is not asked to be strict. Checked here, once, on the schema the vendor will receive,
   * so the mistake surfaces when the tool is bound rather than as a provider's 400 on the first
   * call.
   */
  private static JsonSchema requireObjectAtRoot(
      Tool<?> tool, ObjectMapper mapper, JsonSchema schema) {
    Objects.requireNonNull(schema, "schema must not be null");
    JsonNode root = mapper.readTree(schema.json());
    String found;
    if (root.has("oneOf")) {
      found = "a union (oneOf)";
    } else if (root.has("anyOf")) {
      found = "a union (anyOf)";
    } else if (!root.has("type")) {
      found = "no type at all";
    } else if (!"object".equals(root.get("type").asString())) {
      found = "type '" + root.get("type").asString() + "'";
    } else {
      return schema;
    }
    String name = tool.name().value();
    throw new IllegalArgumentException(
        "tool '"
            + name
            + "': its input schema must be an object at the root, but it is "
            + found
            + "; wrap the type in an object, e.g. record "
            + pascal(name)
            + "Input("
            + tool.inputType().getSimpleName()
            + " value)");
  }

  private static String pascal(String name) {
    StringBuilder out = new StringBuilder();
    boolean upper = true;
    for (char c : name.toCharArray()) {
      if (Character.isLetterOrDigit(c)) {
        out.append(upper ? Character.toUpperCase(c) : c);
        upper = false;
      } else {
        upper = true;
      }
    }
    return out.toString();
  }

  public ToolName name() {
    return tool.name();
  }

  public String description() {
    return tool.description();
  }

  public JsonSchema schema() {
    return schema;
  }

  /**
   * Builds the question this call raises.
   *
   * <p>The action is the sentence stored when the model asked, shown as it is and never worked out
   * again. The arguments are still read into the tool's input type here, before anyone is asked: a
   * call whose arguments will not read cannot run whatever anybody says about it, so there is
   * nothing to gate and the caller discharges it instead. The throw is Jackson's, turned into
   * something the model reads at the one call site that catches it.
   */
  public ApprovalRequest question(
      AgentType agentType,
      AgentId agentId,
      TurnId turn,
      CallId callId,
      String arguments,
      String action,
      Instant askedAt,
      ReplyToken replyToken) {
    mapper.readValue(arguments, tool.inputType());
    ApprovalRequest question =
        new ApprovalRequest(
            agentType,
            agentId,
            turn,
            callId,
            tool.name(),
            arguments,
            action,
            askedAt,
            askedAt.plus(approvalTimeout),
            replyToken);
    // After the action is rendered, so an enricher can read the sentence a person will be
    // shown; before the approver, which is the whole ordering there is. Anything thrown here
    // reaches the handler and discharges the call as one that could not be authorised --
    // which is right, because a gatherer that broke is not a gatherer that found nothing.
    for (ApprovalEnricher enricher : enrichers) {
      enricher.enrich(question);
    }
    return question;
  }

  /**
   * What this call would do, as a line; never throws.
   *
   * <p>The arguments are read into the tool's input type and the action stringifier says it. When
   * the stringifier gives nothing, the line is the tool's name; when the arguments do not read, or
   * the stringifier throws, it says so after the name. A call that has no sentence is still a call,
   * and the turn goes on.
   */
  public String describe(String arguments) {
    String name = tool.name().value();
    I input;
    try {
      input = mapper.readValue(arguments, tool.inputType());
    } catch (RuntimeException e) {
      return name + UNREADABLE;
    }
    String line;
    try {
      line = action.stringify(input);
    } catch (RuntimeException e) {
      LOG.warn("the action stringifier of tool '{}' threw; recording the call without it", name, e);
      return name + UNREADABLE;
    }
    return line == null || line.isBlank() ? name : line;
  }

  /**
   * What this call returned, as a line; never throws, and may be empty.
   *
   * <p>Empty when the result stringifier throws or gives nothing: a result that cannot be said is
   * recorded as saying nothing, and the turn goes on.
   */
  public String rendered(ToolResult.Success success) {
    try {
      String line = result.stringify(success);
      return line == null ? "" : line;
    } catch (RuntimeException e) {
      LOG.warn(
          "the result stringifier of tool '{}' threw; recording the result without it",
          tool.name().value(),
          e);
      return "";
    }
  }

  /** What is recorded for a call to a tool that is not bound. */
  public static String unbound(ToolName name) {
    return name.value() + " (no such tool)";
  }

  /**
   * Asks whether this call may run.
   *
   * <p>Always asked, even when nothing was configured -- {@link Approver#allow()} is a real
   * approver that says yes, not a null to check for. A gate that is sometimes not there is a gate
   * somebody eventually forgets to look for.
   */
  public Awaited<ApprovalResult> approve(ApprovalRequest question) {
    return approver.approve(question);
  }

  public Duration approvalTimeout() {
    return approvalTimeout;
  }

  /**
   * How hard failing to <em>ask</em> is worth repeating -- and never the verdict.
   *
   * <p>Safe to widen where a tool's own policy is not. Asking twice changes nothing in the world:
   * the question is the same question, and an approver that already answered answers the same way.
   * That is the practical difference between this and {@link #retryPolicy()}, and the reason the
   * two are configured apart.
   */
  public RetryPolicy approvalRetryPolicy() {
    return approvalRetryPolicy;
  }

  /** This binding, narrowed to what a provider is allowed to know about it. */
  public ToolOffer offer() {
    return new ToolOffer(tool.name(), tool.description(), schema);
  }

  public Duration timeout() {
    return timeout;
  }

  public RetryPolicy retryPolicy() {
    return retryPolicy;
  }

  /**
   * Decodes what the model wrote and runs the tool on it.
   *
   * <p>A decode failure is returned as a {@link ToolResult.Failure} rather than thrown, because it
   * is the model's mistake and the model is the one who can fix it. Models emit arguments that do
   * not match a schema often enough that it is ordinary, and the useful response is to hand back
   * the complaint so the next attempt is better informed. Throwing would route it through the retry
   * machinery, which would run the identical bad arguments again.
   */
  public Awaited<ToolResult> call(
      AgentType agentType,
      AgentId agentId,
      TurnId turn,
      CallId callId,
      ToolName toolName,
      String json,
      Instant deadline,
      ReplyToken replyToken) {
    I input;
    try {
      input = mapper.readValue(json, tool.inputType());
    } catch (RuntimeException e) {
      return Awaited.ready(
          new ToolResult.Failure("the arguments could not be read: " + e.getMessage()));
    }
    return tool.call(
        new CallRequest<>(agentType, agentId, turn, callId, toolName, input, deadline, replyToken));
  }
}
