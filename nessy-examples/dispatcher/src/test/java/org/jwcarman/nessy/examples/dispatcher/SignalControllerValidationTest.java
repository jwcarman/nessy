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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.examples.dispatcher.SignalController.SignalRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code requireComplete}'s null/blank arms, pinned without Spring or Docker: validation runs
 * before {@link SignalController} ever touches its {@code Agent}, so a {@code null} agent is a safe
 * collaborator here — every case below either throws before that field is read, or (the accepted
 * case) reads it only from a virtual thread the assertion never waits on.
 */
class SignalControllerValidationTest {

  private final SignalController controller = new SignalController(null);

  @Test
  void a_null_body_is_rejected_with_400() {
    assertThatThrownBy(() -> controller.signal(null))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void a_blank_incident_id_is_rejected_with_400() {
    SignalRequest body = new SignalRequest("  ", "water-main", "corner of 5th");

    assertThatThrownBy(() -> controller.signal(body))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void a_missing_kind_is_rejected_with_400() {
    SignalRequest body = new SignalRequest("INC-7", null, "corner of 5th");

    assertThatThrownBy(() -> controller.signal(body))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void a_blank_detail_is_rejected_with_400() {
    SignalRequest body = new SignalRequest("INC-7", "water-main", "   ");

    assertThatThrownBy(() -> controller.signal(body))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void a_complete_body_is_accepted() {
    SignalRequest body = new SignalRequest("INC-7", "water-main", "corner of 5th");

    ResponseEntity<?> response = controller.signal(body);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
  }
}
