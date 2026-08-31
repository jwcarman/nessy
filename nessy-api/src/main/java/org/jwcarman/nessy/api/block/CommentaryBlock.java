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
package org.jwcarman.nessy.api.block;

import java.util.Objects;

/**
 * The model talking while it works — "I'll look that up for you".
 *
 * <p>Distinct from {@link TextBlock}, which is an answer. The difference is not decoration: a
 * reader wants the answer, and progress talk is colour that a transcript may show quietly or not at
 * all. Splitting them means nothing has to infer which it is by checking whether the message
 * happened to make tool calls.
 *
 * <p>Which one a given piece of text becomes is decided in exactly one place — when the turn stops,
 * where the stop reason says whether the model was working or answering.
 */
public record CommentaryBlock(String text) implements ExchangeContentBlock {

  public CommentaryBlock {
    Objects.requireNonNull(text, "text must not be null");
  }
}
