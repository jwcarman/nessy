package org.jwcarman.nessy.api.tool;

import java.util.Objects;
import java.util.Optional;

public sealed interface ApprovalResult {

  /**
   * An opaque pointer at whatever actually decided: a decision id, a ticket number, a hash of an
   * evidence bundle. Empty when nothing stands behind the answer.
   *
   * <p><b>The engine never interprets it.</b> It is a join, and only a join -- from the entry this
   * engine writes down to the record held by the subsystem that saw the request, talked to the
   * people and knows who and why. Pulling that evidence in here would make this a worse audit log
   * than the thing that did the work; leaving the join out would make the two unconnectable.
   */
  Optional<String> reference();

  /** Let it run. */
  record Approved(Optional<String> reference) implements ApprovalResult {

    public Approved {
      Objects.requireNonNull(reference, "reference must not be null");
    }
  }

  /**
   * Do not let it run.
   *
   * <p>Carries a reason where an approval does not, because the arms genuinely differ: a denial's
   * reason is load-bearing -- the call never runs, so the reason becomes the failure the model
   * reads and reacts to -- while an approval has nothing it must say.
   *
   * <p>A reference on both, though. "Who allowed this" and "who refused this" are the same question
   * asked of the same subsystem, and an audit trail that could answer only one of them would be
   * answering the less interesting one.
   */
  record Denied(String reason, Optional<String> reference) implements ApprovalResult {

    public Denied {
      Objects.requireNonNull(reason, "reason must not be null");
      Objects.requireNonNull(reference, "reference must not be null");
    }
  }

  /** Allowed, with nothing standing behind it -- a rule that is its own evidence. */
  static ApprovalResult approved() {
    return new Approved(Optional.empty());
  }

  /**
   * Allowed, and here is where to read who said so and why.
   *
   * <p>Named rather than overloaded, because the argument is not a variation on the same thing: it
   * is written into the story as the only durable link from "this call ran" to the record of the
   * decision behind it. An overload would let that be passed by accident; this cannot be called
   * without meaning to.
   *
   * @param reference the deciding system's own id for the decision -- a ticket, a user id, a hash
   *     of an evidence bundle. Never interpreted here.
   */
  static ApprovalResult approvedBy(String reference) {
    Objects.requireNonNull(reference, "reference must not be null");
    return new Approved(Optional.of(reference));
  }

  static ApprovalResult denied(String reason) {
    return new Denied(reason, Optional.empty());
  }

  /** Refused, and here is where to read who refused it. */
  static ApprovalResult deniedBy(String reason, String reference) {
    Objects.requireNonNull(reference, "reference must not be null");
    return new Denied(reason, Optional.of(reference));
  }
}
