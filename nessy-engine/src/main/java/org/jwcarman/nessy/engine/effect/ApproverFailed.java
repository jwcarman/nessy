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
package org.jwcarman.nessy.engine.effect;

import java.util.Optional;
import org.jwcarman.nessy.api.PayloadRef;

/**
 * An approver threw while it was being asked, carrying the question it was asked.
 *
 * <p>Thrown by {@link ApprovalHandler} in place of the approver's own exception, so the failure
 * that is finally recorded can name the question. To everything else it reads as the exception it
 * wraps: {@link #getCause()} is the approver's exception and {@link #getMessage()} is that
 * exception's message. The dispatcher's retry does not look at what was thrown, only that something
 * was, so a retried ask is retried as before.
 */
final class ApproverFailed extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Held as its text: an exception is serializable, a reference need not be. */
  private final String question;

  ApproverFailed(RuntimeException cause, Optional<PayloadRef> question) {
    super(cause.getMessage(), cause);
    this.question = question.map(PayloadRef::value).orElse(null);
  }

  /** The question the approver was asked, when it could be kept. */
  Optional<PayloadRef> question() {
    return Optional.ofNullable(question).map(PayloadRef::new);
  }
}
