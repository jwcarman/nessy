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
package org.jwcarman.nessy.api;

import java.util.Objects;

/** The model's visible reasoning, with the signature the provider needs to trust it on replay. */
public record ThinkingBlock(String text, String signature) implements ContentBlock {

  public ThinkingBlock {
    Objects.requireNonNull(text, "text must not be null");
    Objects.requireNonNull(signature, "signature must not be null");
  }
}
