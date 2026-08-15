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
package org.jwcarman.nessy.examples.dispatcher;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.examples.dispatcher.CallbackController.OutcomeRequest;
import org.jwcarman.nessy.examples.dispatcher.CallbackController.ProgressRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code requireOutcome}/{@code requireMessage}'s null/blank arms, pinned without Spring or Docker:
 * both guards run before {@link CallbackController} ever touches its {@code Agent}, so a {@code
 * null} agent is a safe collaborator here — every case throws before that field is read (the
 * review's own finding: an empty body previously reached {@code Agent}/{@code ToolResult} unguarded
 * and NPE'd into a 500 instead of a clean 400).
 */
class CallbackControllerValidationTest {

  private final CallbackController controller = new CallbackController(null);

  @Test
  void a_null_outcome_body_is_rejected_with_400() {
    assertThatThrownBy(() -> controller.complete("token", null))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void a_blank_outcome_is_rejected_with_400() {
    OutcomeRequest body = new OutcomeRequest("   ");

    assertThatThrownBy(() -> controller.complete("token", body))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void a_null_progress_body_is_rejected_with_400() {
    assertThatThrownBy(() -> controller.progress("token", null))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void a_blank_progress_message_is_rejected_with_400() {
    ProgressRequest body = new ProgressRequest("   ");

    assertThatThrownBy(() -> controller.progress("token", body))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }
}
