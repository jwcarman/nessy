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
package org.jwcarman.nessy.api.message;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jwcarman.nessy.api.block.AmbientContentBlock;

/**
 * Background the model should have in mind, which nobody said.
 *
 * <p>Saved notes, standing plans, what time it is. Not a turn of the conversation and never part of
 * one: it is assembled at recall, shown once, and thrown away — so it is a {@link ContextMessage}
 * but not a {@link Message}, and there is no door through which it could reach a transcript.
 *
 * <p><b>Where it lands, and how it is labelled, is the provider's business.</b> Each vendor carries
 * background differently — a top-level system field, a developer message, a system instruction —
 * and each has its own idea of how to mark a section: Anthropic's own guidance asks for XML tags,
 * another vendor may want a heading or nothing. So this says what the background IS and leaves the
 * rendering to the adapter that knows the vendor.
 */
public record AmbientMessage(String kind, List<AmbientContentBlock> content)
    implements ContextMessage {

  /**
   * What a kind may look like.
   *
   * <p>Lowercase kebab-case, and that is a SAFETY rule rather than a style one: an adapter may
   * interpolate a kind into markup, and an unconstrained one could write structure into a prompt.
   * Checked here, so no adapter has to escape anything.
   */
  private static final Pattern KIND_PATTERN = Pattern.compile("[a-z][a-z0-9-]*");

  public AmbientMessage {
    Objects.requireNonNull(kind, "kind must not be null");
    if (!KIND_PATTERN.matcher(kind).matches()) {
      throw new IllegalArgumentException(
          "kind must be lowercase kebab-case starting with a letter: '" + kind + "'");
    }
    Objects.requireNonNull(content, "content must not be null");
    if (content.isEmpty()) {
      throw new IllegalArgumentException(
          "content must not be empty; say nothing by adding nothing");
    }
    content = List.copyOf(content);
  }
}
