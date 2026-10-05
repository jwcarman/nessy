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

import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * An approver threw while it was being asked, carrying the facts it was shown.
 *
 * <p>Thrown by {@link ApprovalHandler} in place of the approver's own exception, so the failure
 * that is finally recorded can hold the facts. To everything else it reads as the exception it
 * wraps: {@link #getCause()} is the approver's exception and {@link #getMessage()} is that
 * exception's message. The dispatcher's retry does not look at what was thrown, only that something
 * was: the failure is a throw, so the dispatcher's retry policy decides whether to ask again.
 */
final class ApproverFailed extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Its own copy of the facts as they stood. Not serialized: a JSON tree is not serializable. */
  private final transient ObjectNode facts;

  ApproverFailed(RuntimeException cause, ObjectNode facts) {
    super(cause.getMessage(), cause);
    this.facts = facts == null ? JsonNodeFactory.instance.objectNode() : facts.deepCopy();
  }

  /**
   * The facts the approver was shown, as they stood when it threw; an empty object when there were
   * none, and after the exception has been deserialized.
   */
  ObjectNode facts() {
    return facts == null ? JsonNodeFactory.instance.objectNode() : facts;
  }
}
