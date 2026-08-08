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
package org.jwcarman.nessy.core;

import java.util.List;
import java.util.Objects;

/** One turn of the conversation, as a role and its content blocks. */
public record Message(Role role, List<ContentBlock> content) {

  public Message {
    Objects.requireNonNull(role, "role must not be null");
    content = List.copyOf(content);
  }

  public static Message user(String text) {
    return new Message(Role.USER, List.of(new TextBlock(text)));
  }

  public static Message assistant(List<ContentBlock> content) {
    return new Message(Role.ASSISTANT, content);
  }

  /** Tool results go back as a user message — see {@link ToolResultBlock}. */
  public static Message toolResults(List<ContentBlock> results) {
    return new Message(Role.USER, results);
  }
}
