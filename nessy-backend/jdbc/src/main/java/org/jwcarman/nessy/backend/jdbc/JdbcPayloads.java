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

package org.jwcarman.nessy.backend.jdbc;

import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.IdentityCodec;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.backend.payload.Payloads;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * Content in {@code nessy_payload}, addressed by its own hash and scoped to one agent.
 *
 * <p>Inputs, answers, tool results and JSON documents are written here, and what goes beside the
 * record of an agent's life is a reference. The exception is two lines of text per tool call, what
 * the call would do and what it returned, each at most 1,000 characters, which stay in the events.
 * The summaries of an agent's chapters are in {@code nessy_chapter}. So this table is where most of
 * what an agent said lives and not all of it.
 *
 * <p><b>Addressed, not minted.</b> The reference is the SHA-256 of the content as the value codec
 * writes it, before the storage transform, so putting the same content twice is one row and the
 * same reference. An effect retried after a failure cannot leave a second copy, and a reference
 * proves what is behind it.
 *
 * <p><b>Scoped, not shared.</b> Two agents that say the same thing store it twice. That is
 * deliberate: removing an agent's payload rows is then one statement over one table, with nothing
 * shared out from under anybody. That statement does not reach the lines in its events or the
 * summaries of its chapters. Counting references across agents would save a little space and cost
 * the one property this table exists for.
 */
public final class JdbcPayloads implements Payloads {

  private static final String PUT =
      """
      INSERT INTO nessy_payload (agent_id, hash, kind, content)
      VALUES (?, ?, ?, ?)
          ON CONFLICT (agent_id, hash) DO NOTHING
      """;

  private static final String BLOCKS = "BLOCKS";
  private static final String DOCUMENT = "DOCUMENT";

  /** One row as read: its kind, and the encoded bytes not yet decoded. */
  private record Stored(String kind, byte[] content) {
    @Override
    public boolean equals(Object other) {
      return other instanceof Stored(String otherKind, byte[] otherContent)
          && kind.equals(otherKind)
          && Arrays.equals(content, otherContent);
    }

    @Override
    public int hashCode() {
      return 31 * kind.hashCode() + Arrays.hashCode(content);
    }

    @Override
    public String toString() {
      return "Stored[kind=" + kind + ", content=" + Arrays.toString(content) + "]";
    }
  }

  private static final String GET =
      "SELECT hash, kind, content FROM nessy_payload WHERE agent_id = ? AND hash = ANY (?)";

  private final JdbcClient jdbc;

  private final Codec<Payloads.Content> blocksCodec;
  private final Codec<Payloads.Document> documentCodec;
  private final Codec<byte[]> transform;
  private final AgentId agent;

  /**
   * With no storage transform of its own: the reference is a hash of whatever {@code codecs}
   * writes. A factory that already includes a transform is therefore hashed after it; use {@link
   * #JdbcPayloads(JdbcClient, CodecFactory, Codec)} when there is a transform.
   */
  public JdbcPayloads(JdbcClient jdbc, CodecFactory codecs) {
    this(jdbc, codecs, IdentityCodec.INSTANCE);
  }

  /**
   * With the value codec and the storage transform given apart, so the reference can be a hash of
   * the content before the transform: the same content is one reference and one row even when the
   * transform never writes the same bytes twice. Use this one when there is a transform.
   *
   * @param values writes a value as bytes, with no transform applied
   * @param transform applied to those bytes on the way in and undone on the way out
   */
  public JdbcPayloads(JdbcClient jdbc, CodecFactory values, Codec<byte[]> transform) {
    this(
        jdbc,
        Objects.requireNonNull(values, "values must not be null").create(Payloads.Content.class),
        values.create(Payloads.Document.class),
        Objects.requireNonNull(transform, "transform must not be null"),
        null);
  }

  private JdbcPayloads(
      JdbcClient jdbc,
      Codec<Payloads.Content> blocksCodec,
      Codec<Payloads.Document> documentCodec,
      Codec<byte[]> transform,
      AgentId agent) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    this.blocksCodec = blocksCodec;
    this.documentCodec = documentCodec;
    this.transform = transform;
    this.agent = agent;
  }

  @Override
  public Payloads forAgent(AgentId agent) {
    return new JdbcPayloads(
        jdbc,
        blocksCodec,
        documentCodec,
        transform,
        Objects.requireNonNull(agent, "agent must not be null"));
  }

  @Override
  public PayloadRef put(List<? extends Block> content) {
    return keep(BLOCKS, blocksCodec.encode(new Payloads.Content(List.copyOf(content))));
  }

  @Override
  public PayloadRef putDocument(JsonNode document) {
    return keep(DOCUMENT, documentCodec.encode(new Payloads.Document(document)));
  }

  private PayloadRef keep(String kind, byte[] plain) {
    AgentId scope = scoped();
    PayloadRef ref = Payloads.reference(plain);
    jdbc.sql(PUT)
        .params(scope.value(), HexFormat.of().parseHex(ref.value()), kind, transform.encode(plain))
        .update();
    return ref;
  }

  @Override
  public JsonNode getDocument(PayloadRef ref) {
    List<Stored> rows =
        jdbc.sql(GET)
            .params(scoped().value(), new byte[][] {HexFormat.of().parseHex(ref.value())})
            .query((rs, _) -> new Stored(rs.getString("kind"), rs.getBytes("content")))
            .list();
    if (rows.isEmpty()) {
      throw new IllegalStateException("no payload behind " + ref);
    }
    Stored row = rows.getFirst();
    return switch (row.kind()) {
      case DOCUMENT -> documentCodec.decode(transform.decode(row.content())).document();
      case BLOCKS ->
          throw new IllegalStateException("payload " + ref + " holds blocks, not a document");
      default -> throw unknownKind(ref, row.kind());
    };
  }

  @Override
  public Resolved get(PayloadRef ref) {
    return get(List.of(ref)).getOrDefault(ref, new Resolved.Missing());
  }

  @Override
  public Map<PayloadRef, Resolved> get(Collection<PayloadRef> refs) {
    Set<PayloadRef> wanted = new LinkedHashSet<>(refs);
    Map<PayloadRef, Resolved> found = new LinkedHashMap<>();
    for (PayloadRef ref : wanted) {
      // Every reference gets an answer, so a caller never has to tell "not asked" from "not there".
      found.put(ref, new Resolved.Missing());
    }
    if (wanted.isEmpty()) {
      return found;
    }
    List<byte[]> hashes = wanted.stream().map(ref -> HexFormat.of().parseHex(ref.value())).toList();
    jdbc.sql(GET)
        .params(scoped().value(), hashes.toArray(byte[][]::new))
        .query(
            (rs, _) -> {
              PayloadRef ref = new PayloadRef(HexFormat.of().formatHex(rs.getBytes("hash")));
              String kind = rs.getString("kind");
              switch (kind) {
                case BLOCKS ->
                    found.put(
                        ref,
                        new Resolved.Found(
                            blocksCodec.decode(transform.decode(rs.getBytes("content"))).blocks()));
                case DOCUMENT ->
                    throw new IllegalStateException(
                        "payload " + ref + " holds a document, not blocks");
                default -> throw unknownKind(ref, kind);
              }
              return ref;
            })
        .list();
    return found;
  }

  private static IllegalStateException unknownKind(PayloadRef ref, String kind) {
    return new IllegalStateException("payload " + ref + " has an unknown kind: " + kind);
  }

  private AgentId scoped() {
    if (agent == null) {
      throw new IllegalStateException("this store is not scoped to an agent; call forAgent first");
    }
    return agent;
  }
}
