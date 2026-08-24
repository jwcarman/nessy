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
package org.jwcarman.nessy.agent;

import java.util.Objects;

/**
 * The computation a call is currently in flight under.
 *
 * @param computationId the Continuum computation id, as its opaque string value
 * @param kind which client owns it
 */
public record DispatchEntry(String computationId, DispatchKind kind) {

  public DispatchEntry {
    Objects.requireNonNull(computationId, "computationId must not be null");
    Objects.requireNonNull(kind, "kind must not be null");
  }

  /** Which Continuum client a dispatch entry belongs to. */
  public enum DispatchKind {
    /** An approval computation, awaiting a human decision. */
    APPROVAL,
    /** A tool computation, awaiting an external result. */
    TOOL
  }
}
