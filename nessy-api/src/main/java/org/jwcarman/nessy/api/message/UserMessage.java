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
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.block.UserContentBlock;

/** Everything the world says to the model that is not a tool answering a call. */
public record UserMessage(List<UserContentBlock> content) implements Message {

  public UserMessage {
    Objects.requireNonNull(content, "content must not be null");
    content = List.copyOf(content);
  }

  /** The common case: a line of prose. */
  public static UserMessage of(String text) {
    Objects.requireNonNull(text, "text must not be null");
    return new UserMessage(List.of(new TextBlock(text)));
  }
}
