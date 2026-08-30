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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Objects;

/**
 * What an approver decided: let the call run, or stop it and say why.
 *
 * <p>Sealed rather than a flag because the arms genuinely differ. A denial's reason is load-bearing
 * — the call never runs, so the reason becomes the failure the model reads and reacts to — while an
 * approval has nothing it must carry. A single record with a {@code reason} field would make that
 * field mandatory half the time and meaningless the other half, enforced by a runtime check instead
 * of the compiler.
 *
 * <p>Nothing on the wire corresponds to this; it is entirely Nessy's concept, so it is free to take
 * the shape that reads best rather than the shape a provider's JSON happens to use.
 */
/** Wire names are a compatibility surface: a persisted decision names them. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = ApprovalResult.Approved.class, name = "approved"),
  @JsonSubTypes.Type(value = ApprovalResult.Denied.class, name = "denied")
})
public sealed interface ApprovalResult {

  /** Let it run. */
  record Approved() implements ApprovalResult {}

  /** Do not let it run. {@code reason} reaches the model as the call's failure. */
  record Denied(String reason) implements ApprovalResult {
    public Denied {
      Objects.requireNonNull(reason, "reason must not be null");
    }
  }

  static ApprovalResult approved() {
    return new Approved();
  }

  static ApprovalResult denied(String reason) {
    return new Denied(reason);
  }
}
