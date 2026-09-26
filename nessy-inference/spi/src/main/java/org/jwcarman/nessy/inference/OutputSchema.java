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
package org.jwcarman.nessy.inference;

import java.util.Objects;

/**
 * The shape an answer must come back in: JSON Schema, as a string.
 *
 * <p>Deliberately the same shape as {@link org.jwcarman.nessy.api.tool.InputSchema}, which is how a
 * tool says what it takes. One is what goes in, the other is what must come out, and neither has
 * any business parsing the other's schema.
 *
 * <p><b>How it is satisfied is the adapter's business and nobody else's.</b> A provider with a
 * native facility -- OpenAI's {@code response_format}, Gemini's response schema, Anthropic's output
 * format -- uses it. One without falls back to offering a hidden tool whose input schema is this,
 * and unwrapping the call it gets back. Either way the caller is handed an {@link
 * InferenceResult.Answer} whose text is JSON matching this schema, and never learns which happened.
 *
 * <p>That is why this exists rather than a capability signal every caller has to negotiate: the one
 * place that knows what a vendor can do is the adapter for that vendor.
 */
public record OutputSchema(String json) {

  public OutputSchema {
    Objects.requireNonNull(json, "json must not be null");
    if (json.isBlank()) {
      throw new IllegalArgumentException("json must not be blank");
    }
  }
}
