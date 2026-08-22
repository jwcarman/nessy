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
package org.jwcarman.nessy.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.agent.codec.MessageCodec;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.spi.Memory;
import org.jwcarman.nessy.spi.substrate.Codec;
import org.jwcarman.nessy.spi.substrate.ConflictException;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * The {@code memory} recipe (substrate spec §6.2): one journal per scope, keyed by {@code agentId},
 * one entry per message. {@link #remember(Message)} appends at head + 1; a conflicting racer means
 * someone else appended first, so the head is re-read and the append retried — near-zero in
 * practice since the scope CAS already serializes turns, but correct under a genuine race. {@link
 * #recall()} folds every entry from seq 1 into a {@link Context}. The transcript is the permanent
 * record: nothing here ever rewrites an entry.
 *
 * <p>The stored shape is a {@link Codec}{@code <}{@link Message}{@code >} (spec §3, §7): the {@link
 * #SubstrateMemory(Substrate, String, ObjectMapper)} constructor defaults it to the {@link
 * MessageCodec} binding; {@link #SubstrateMemory(Substrate, String, Codec)} accepts a
 * caller-supplied codec directly — a transform chained on with {@link Codec#then(Codec)}
 * (encryption, compression) or a test probe.
 */
public final class SubstrateMemory implements Memory {

  private static final String KIND = "memory";

  private final Substrate store;
  private final String agentId;
  private final Codec<Message> codec;

  /** Defaults the stored shape to the {@link MessageCodec} binding over {@code mapper}. */
  public SubstrateMemory(Substrate store, String agentId, ObjectMapper mapper) {
    this(
        store,
        agentId,
        new MessageCodecAdapter(
            new MessageCodec(Objects.requireNonNull(mapper, "mapper must not be null"))));
  }

  public SubstrateMemory(Substrate store, String agentId, Codec<Message> codec) {
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.agentId = Objects.requireNonNull(agentId, "agentId must not be null");
    this.codec = Objects.requireNonNull(codec, "codec must not be null");
  }

  @Override
  public void remember(Message message) {
    Objects.requireNonNull(message, "message must not be null");
    byte[] payload = codec.encode(message);
    while (true) {
      long nextSeq = head() + 1;
      try {
        store.append(KIND, agentId, nextSeq, payload);
        return;
      } catch (ConflictException _) {
        // another writer took nextSeq first; re-read the head and retry
      }
    }
  }

  @Override
  public Context recall() {
    List<Message> messages =
        store.entries(KIND, agentId, 1).stream()
            .map(entry -> codec.decode(entry.payload()))
            .toList();
    return Context.of(messages);
  }

  private long head() {
    List<Substrate.Entry> entries = store.entries(KIND, agentId, 1);
    return entries.isEmpty() ? 0L : entries.getLast().seq();
  }

  /**
   * Adapts {@link MessageCodec}'s String-JSON binding to the byte-oriented {@link Codec} seam —
   * internal, not a new public type (spec §3, §7).
   */
  private static final class MessageCodecAdapter implements Codec<Message> {

    private final MessageCodec codec;

    private MessageCodecAdapter(MessageCodec codec) {
      this.codec = codec;
    }

    @Override
    public byte[] encode(Message message) {
      return codec.toJson(message).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public Message decode(byte[] bytes) {
      return codec.message(new String(bytes, StandardCharsets.UTF_8));
    }
  }
}
