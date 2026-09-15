package org.jwcarman.nessy.approval.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jwcarman.nessy.approval.intent.Fixtures.AGENT;
import static org.jwcarman.nessy.approval.intent.Fixtures.MAPPER;
import static org.jwcarman.nessy.approval.intent.Fixtures.freshStore;
import static org.jwcarman.nessy.approval.intent.Fixtures.request;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.Approver;

class IntentPolicyTest {

  @Test
  void an_undeclared_call_is_denied_and_told_how_to_proceed() {
    var policy =
        IntentPolicy.requireDeclared(new IntentEnricher<>(freshStore(), MAPPER), Approver.allow());

    Awaited<ApprovalResult> result = policy.approve(request());

    assertThat(result).isInstanceOf(Awaited.Ready.class);
    var answer = ((Awaited.Ready<ApprovalResult>) result).value();
    assertThat(answer).isInstanceOf(ApprovalResult.Denied.class);
    assertThat(((ApprovalResult.Denied) answer).reason()).contains("declare-intent");
  }

  @Test
  void a_declared_call_passes_to_the_approver_it_guards() {
    var store = freshStore();
    store.declare(AGENT, new Intent("restart prod-eu to clear the stuck deploy"));
    var policy =
        IntentPolicy.requireDeclared(new IntentEnricher<>(store, MAPPER), Approver.allow());

    assertThat(policy.approve(request())).isEqualTo(Awaited.ready(ApprovalResult.approved()));
  }

  /** A declaration is a precondition, never a reason to allow: the guarded approver still rules. */
  @Test
  void it_never_approves_on_its_own_only_defers_to_what_it_guards() {
    var store = freshStore();
    store.declare(AGENT, new Intent("declared, but still not allowed"));
    Approver alwaysDenies = request -> Awaited.ready(ApprovalResult.denied("policy says no"));
    var policy = IntentPolicy.requireDeclared(new IntentEnricher<>(store, MAPPER), alwaysDenies);

    assertThat(policy.approve(request()))
        .isEqualTo(Awaited.ready(ApprovalResult.denied("policy says no")));
  }

  @Test
  void the_guarded_approver_never_runs_when_nothing_was_declared() {
    var calls = new AtomicInteger();
    Approver counting =
        request -> {
          calls.incrementAndGet();
          return Awaited.ready(ApprovalResult.approved());
        };
    var policy = IntentPolicy.requireDeclared(new IntentEnricher<>(freshStore(), MAPPER), counting);

    policy.approve(request());

    assertThat(calls).hasValue(0);
  }

  @Test
  void the_guarded_approver_sees_the_declaration_this_policy_recorded() {
    var store = freshStore();
    store.declare(AGENT, new Intent("restart prod-eu"));
    Approver reader =
        request ->
            Awaited.ready(
                request.fact(IntentEnricher.DECLARED).isPresent()
                    ? ApprovalResult.approved()
                    : ApprovalResult.denied("the fact did not reach me"));
    var policy = IntentPolicy.requireDeclared(new IntentEnricher<>(store, MAPPER), reader);

    assertThat(policy.approve(request())).isEqualTo(Awaited.ready(ApprovalResult.approved()));
  }
}
