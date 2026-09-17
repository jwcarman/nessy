package org.jwcarman.nessy.engine.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.Approver;

/**
 * An approver whose every question is a {@code nessy.approval} span: which call was asked about,
 * whose it was, and what the answer came to -- approved, denied, or put to a person.
 */
public final class ObservedApprover {

  private static final String DURATION = "gen_ai.client.operation.duration";
  private static final String OPERATION_NAME = "gen_ai.operation.name";
  private static final String TOOL_NAME = "gen_ai.tool.name";

  private ObservedApprover() {}

  public static Approver wrap(Approver delegate, ObservationRegistry observations) {
    Objects.requireNonNull(delegate, "delegate must not be null");
    Objects.requireNonNull(observations, "observations must not be null");
    return request -> {
      if (observations.isNoop()) {
        return delegate.approve(request);
      }
      Observation observation =
          Observation.createNotStarted("nessy.approval", observations)
              .contextualName("approve " + request.toolName().value())
              .lowCardinalityKeyValue(TOOL_NAME, request.toolName().value())
              .highCardinalityKeyValue("gen_ai.tool.call.id", request.callId().value())
              .lowCardinalityKeyValue("nessy.approval.answer", "none");
      new Identity(request.agentType(), request.agentId()).on(observation, request.turn());
      return observation.observe(
          () -> {
            Awaited<ApprovalResult> answer = delegate.approve(request);
            observation.lowCardinalityKeyValue("nessy.approval.answer", approvalOf(answer));
            return answer;
          });
    };
  }

  private static String approvalOf(Awaited<ApprovalResult> answer) {
    return switch (answer) {
      case Awaited.Deferred<ApprovalResult> _ -> "asked-a-person";
      case Awaited.Ready<ApprovalResult>(var result) ->
          result instanceof ApprovalResult.Approved ? "approved" : "denied";
    };
  }
}
