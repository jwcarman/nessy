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
package org.jwcarman.nessy.spi.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.internal.MessageJson;

/** The default {@link MessageCodec#json(ObjectMapper)} implementation: canonical JSON, UTF-8. */
final class JsonMessageCodec implements MessageCodec {

  private final ObjectMapper mapper;

  JsonMessageCodec(ObjectMapper mapper) {
    this.mapper = MessageJson.mapperFor(Objects.requireNonNull(mapper, "mapper must not be null"));
  }

  @Override
  public byte[] encode(Message message) {
    Objects.requireNonNull(message, "message must not be null");
    try {
      return mapper.writeValueAsBytes(message);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public Message decode(byte[] bytes) {
    Objects.requireNonNull(bytes, "bytes must not be null");
    try {
      return mapper.readValue(bytes, Message.class);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
