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
import org.jwcarman.nessy.api.block.AnswerContentBlock;

/**
 * The assistant's answer: what it says when no homework remains.
 *
 * <p>Not called an assistant message, though every vendor sends it under {@code role: assistant}:
 * so is an {@link ExchangeMessage}, and naming one of two assistant turns for the speaker invites
 * the reader to believe the other one is somebody else's.
 *
 * <p>What the model said.
 *
 * <p><b>Content only.</b> Why a turn ended and what it cost are facts about the CALL, not content
 * of the message: they are never sent back on replay, so putting them here would make this type a
 * superset of the wire shape that every adapter has to remember to strip. They belong on the model
 * call's result instead.
 */
public record AnswerMessage(List<AnswerContentBlock> content) implements HistoryMessage {

  public AnswerMessage {
    Objects.requireNonNull(content, "content must not be null");
    content = List.copyOf(content);
  }
}
