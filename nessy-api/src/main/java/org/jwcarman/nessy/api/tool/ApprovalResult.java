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
package org.jwcarman.nessy.api.tool;

import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.Truncator;

public sealed interface ApprovalResult {

  /**
   * Who or what decided, as the application chooses to say it: a user id, a ticket number, a
   * policy's name. Empty when nobody is named.
   *
   * <p><b>Nessy never interprets it.</b> It is written into the story as given. The record of the
   * decision itself stays with the application, and the call's {@code IdempotencyKey}, which is on
   * every event about the call, is the join to it.
   *
   * <p>The value is the application's own and is kept as given, with one limit: one longer than
   * {@value ToolConfig#LINE_CAP} characters is cut to that length, keeping its start and ending in
   * {@code ...}. Nothing else is changed; a blank value, or one with newlines, stays as it is.
   */
  Optional<String> decidedBy();

  /** Let it run. */
  record Approved(Optional<String> decidedBy) implements ApprovalResult {

    public Approved {
      decidedBy = capped(decidedBy);
    }
  }

  /**
   * Do not let it run.
   *
   * <p>Carries a reason where an approval does not, because the arms genuinely differ: a denial's
   * reason is load-bearing -- the call never runs, so the reason becomes the failure the model
   * reads and reacts to -- while an approval has nothing it must say.
   *
   * <p>Both name who decided, though. "Who allowed this" and "who refused this" are the same
   * question asked of the same subsystem, and an audit trail that could answer only one of them
   * would be answering the less interesting one.
   *
   * <p>The reason is the application's own and is kept as given, cut to {@value
   * ToolConfig#LINE_CAP} characters if it is longer, the same way a tool's failure message is.
   */
  record Denied(String reason, Optional<String> decidedBy) implements ApprovalResult {

    public Denied {
      Objects.requireNonNull(reason, "reason must not be null");
      reason = Truncator.dropMiddle().truncate(reason, ToolConfig.LINE_CAP);
      decidedBy = capped(decidedBy);
    }
  }

  /** Allowed, with nothing standing behind it -- a rule that is its own evidence. */
  static ApprovalResult approved() {
    return new Approved(Optional.empty());
  }

  /**
   * Allowed, and here is who or what said so.
   *
   * <p>Named rather than overloaded, because the argument is not a variation on the same thing: it
   * is written into the story, and an overload would let it be passed by accident.
   *
   * @param decidedBy who or what decided, as the application says it -- a user id, a ticket, a
   *     policy's name. Never interpreted here; kept as given, cut to {@value ToolConfig#LINE_CAP}
   *     characters if longer.
   */
  static ApprovalResult approvedBy(String decidedBy) {
    requireDecidedBy(decidedBy);
    return new Approved(Optional.of(decidedBy));
  }

  static ApprovalResult denied(String reason) {
    return new Denied(reason, Optional.empty());
  }

  /**
   * Refused, and here is who or what refused it. The reason and the decider are the application's
   * own, kept as given and cut to {@value ToolConfig#LINE_CAP} characters if longer.
   */
  static ApprovalResult deniedBy(String reason, String decidedBy) {
    requireDecidedBy(decidedBy);
    return new Denied(reason, Optional.of(decidedBy));
  }

  /** The decider, refused if null, and cut to the line cap, keeping its start, if longer. */
  private static Optional<String> capped(Optional<String> decidedBy) {
    requireDecidedBy(decidedBy);
    return decidedBy.map(value -> Truncator.dropTail().truncate(value, ToolConfig.LINE_CAP));
  }

  /** Either form of decidedBy -- the value, or an {@code Optional} of it -- must not be null. */
  private static void requireDecidedBy(Object decidedBy) {
    Objects.requireNonNull(decidedBy, "decidedBy must not be null");
  }
}
