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
package org.jwcarman.nessy;

import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jwcarman.nessy.api.Message;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.Role;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.SessionState;
import org.jwcarman.nessy.api.SessionStatus;
import org.jwcarman.nessy.api.TextBlock;

/** What came back. Sugar over the final {@link SessionState}. */
public record Reply(RunOutcome outcome) {

  public Reply {
    Objects.requireNonNull(outcome, "outcome must not be null");
  }

  public SessionState state() {
    return switch (outcome) {
      case RunOutcome.Completed(SessionState state) -> state;
      case RunOutcome.Parked(SessionState state, ParkToken ignored) -> state;
    };
  }

  /** The prose of the last assistant message; empty if there is none. */
  public String text() {
    return state().messages().stream()
        .filter(message -> message.role() == Role.ASSISTANT)
        .reduce((first, second) -> second)
        .map(Reply::proseOf)
        .orElse("");
  }

  public boolean failed() {
    return state().status() == SessionStatus.FAILED;
  }

  public Optional<String> failureReason() {
    return Optional.ofNullable(state().failureReason());
  }

  private static String proseOf(Message message) {
    return message.content().stream()
        .filter(TextBlock.class::isInstance)
        .map(TextBlock.class::cast)
        .map(TextBlock::text)
        .collect(Collectors.joining());
  }
}
