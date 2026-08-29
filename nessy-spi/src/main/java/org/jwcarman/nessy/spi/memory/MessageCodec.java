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
package org.jwcarman.nessy.spi.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.spi.codec.Codecs;

/**
 * Internal storage machinery: renders the {@link Message}/{@link Context} vocabulary to and from
 * the JSON the byte-payload substrate persists (spec §7). Not API vocabulary — nothing here is
 * meant to be called outside the recipes that back {@code Memory} and friends.
 *
 * <p>{@link Message}, {@link Context}, and every {@code ContentBlock} carry their own Jackson
 * annotations (spec §7); this codec is the mapper-binding boundary, not a tree-walker — it only
 * adds the field-naming precheck the mapper cannot phrase as loudly as the wire-format contract
 * demands, and translates every Jackson failure into an {@link IllegalArgumentException} naming the
 * offense.
 *
 * <p>Wraps one caller-supplied, already-pinned {@link ObjectMapper} (spec §7) — no static mapper
 * survives here.
 */
public final class MessageCodec {

  private static final String MESSAGE = "message";
  private static final String CONTEXT = "context";

  private final Codecs codecs;

  public MessageCodec(ObjectMapper mapper) {
    this.codecs = new Codecs(mapper);
  }

  public String toJson(Message message) {
    Objects.requireNonNull(message, "message must not be null");
    return codecs.write(message);
  }

  public Message message(String json) {
    Objects.requireNonNull(json, "json must not be null");
    JsonNode root = codecs.readTree(json, MESSAGE);
    Codecs.requireArrayIfPresent(root, "content", MESSAGE);
    return codecs.bind(root, Message.class, MESSAGE);
  }

  public String toJson(Context context) {
    Objects.requireNonNull(context, "context must not be null");
    return codecs.write(context);
  }

  public Context context(String json) {
    Objects.requireNonNull(json, "json must not be null");
    JsonNode root = codecs.readTree(json, CONTEXT);
    Codecs.requireArrayIfPresent(root, "messages", CONTEXT);
    return codecs.bind(root, Context.class, CONTEXT);
  }
}
