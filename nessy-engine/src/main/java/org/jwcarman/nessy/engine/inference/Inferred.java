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

import java.util.Objects;
import org.jwcarman.nessy.backend.event.RequestManifest;
import org.jwcarman.nessy.inference.InferenceResult;

/**
 * What one inference came to: the model's result, and the manifest of the request that was sent to
 * get it.
 *
 * @param result what the model said
 * @param request what the request was made of, each part stored and named by reference
 */
public record Inferred(InferenceResult result, RequestManifest request) {

  public Inferred {
    Objects.requireNonNull(result, "result must not be null");
    Objects.requireNonNull(request, "request must not be null");
  }
}
