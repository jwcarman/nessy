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
package org.jwcarman.nessy.engine.inference;

import java.util.List;
import java.util.Optional;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.backend.event.RequestManifest;

/**
 * A small fixed manifest, for the tests that need an {@link Inferred} and care about nothing in it.
 */
public final class Manifests {

  private Manifests() {}

  /** A manifest that differs from every other number's: {@code n} is 0 to 9. */
  public static RequestManifest numbered(int n) {
    PayloadRef ref = new PayloadRef(Integer.toString(n).repeat(64));
    return new RequestManifest(
        "0.0.0",
        ref,
        ref,
        Optional.empty(),
        ref,
        Optional.empty(),
        Optional.empty(),
        List.of(),
        List.of(),
        List.of());
  }

  public static RequestManifest any() {
    PayloadRef ref = new PayloadRef("0".repeat(64));
    return new RequestManifest(
        "0.0.0",
        ref,
        ref,
        Optional.empty(),
        ref,
        Optional.empty(),
        Optional.empty(),
        List.of(),
        List.of(),
        List.of());
  }
}
