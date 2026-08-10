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
package org.jwcarman.nessy.spi.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jwcarman.nessy.api.message.Message;

/**
 * Turns a {@link Message} into bytes and back, for whatever durable module wants to store or
 * transmit a transcript entry.
 *
 * <p>The {@code api} zone stays annotation-free on principle: {@code Message} and {@link
 * org.jwcarman.nessy.api.message.ContentBlock} carry no Jackson wiring of their own. {@link #json}
 * gets its polymorphism from {@code internal.MessageJson} instead, which is free to depend on
 * Jackson however it needs to.
 */
public interface MessageCodec {

  byte[] encode(Message message);

  Message decode(byte[] bytes);

  /** Canonical JSON, encoded as UTF-8 bytes. */
  static MessageCodec json(ObjectMapper mapper) {
    return new JsonMessageCodec(mapper);
  }
}
