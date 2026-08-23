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
import org.jwcarman.nessy.api.tool.ComputationId;

/**
 * Get-or-create's answer (preamble ruling 4): {@code created} is false when the computation already
 * existed — the submit-once discipline's signal not to re-submit external work.
 *
 * <p>Package-private (computation-identity spec §2 addendum, the whittle ruling): {@link
 * SubstrateComputations#create}'s package-private ops-seam overload is the one caller reached from
 * outside {@code org.jwcarman.nessy.agent}, and it too stays package-private — no desk's public
 * signature carries this type.
 */
record CreateResult(ComputationId id, boolean created) {

  public CreateResult {
    Objects.requireNonNull(id, "id must not be null");
  }
}
