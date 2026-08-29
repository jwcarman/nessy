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
package org.jwcarman.nessy.engine;

import java.util.List;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.tool.ToolCall;

/**
 * What one model turn produced, in Nessy's own vocabulary.
 *
 * <p>{@code message} is the assistant turn exactly as it will be remembered — no translation
 * anywhere, because {@code Memory} stores {@link Message}s and the provider produces them.
 */
public sealed interface ModelReply {

  Message message();

  Usage usage();

  /** The model talked and asked for nothing. */
  record Said(Message message, Usage usage) implements ModelReply {}

  /** The model asked for tools. */
  record AskedForTools(Message message, List<ToolCall> calls, Usage usage) implements ModelReply {
    public AskedForTools {
      calls = List.copyOf(calls);
    }
  }

  /** The call failed; the round ends with a note rather than a crash. */
  record Failed(Message message, Usage usage, String detail) implements ModelReply {}
}
