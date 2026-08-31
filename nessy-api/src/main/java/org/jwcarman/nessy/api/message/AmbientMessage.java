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
import org.jwcarman.nessy.api.block.AmbientContentBlock;

/**
 * Background the model should have in mind, which nobody said.
 *
 * <p>Saved notes, standing plans, what time it is. Not a turn of the conversation and never part of
 * one: it is assembled at recall, shown once, and thrown away — so it is a {@link ContextMessage}
 * but not a {@link Message}, and there is no door through which it could reach a transcript.
 *
 * <p><b>Where it lands is the provider's business.</b> Each vendor carries background differently —
 * a top-level system field, a developer message, a system instruction — so an adapter decides, and
 * this says only what the content is.
 */
public record AmbientMessage(List<AmbientContentBlock> content) implements ContextMessage {

  public AmbientMessage {
    Objects.requireNonNull(content, "content must not be null");
    if (content.isEmpty()) {
      throw new IllegalArgumentException(
          "content must not be empty; say nothing by adding nothing");
    }
    content = List.copyOf(content);
  }
}
