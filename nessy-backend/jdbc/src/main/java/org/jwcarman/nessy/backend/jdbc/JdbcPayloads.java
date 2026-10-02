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
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.backend.payload.Payloads;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Content in {@code nessy_payload}, addressed by its own hash and scoped to one agent.
 *
 * <p>Inputs, answers and tool results are written here, and what goes beside the record of an
 * agent's life is a reference. The exception is two bounded lines of text per tool call, which stay
 * in the events, so this table is where most of what an agent said lives and not all of it.
 *
 * <p><b>Addressed, not minted.</b> The reference is the SHA-256 of the encoded content, so putting
 * the same content twice is one row and the same reference. An effect retried after a failure
 * cannot leave a second copy, and a reference proves what is behind it.
 *
 * <p><b>Scoped, not shared.</b> Two agents that say the same thing store it twice. That is
 * deliberate: forgetting an agent is then one statement over one table, with nothing shared out
 * from under anybody. Counting references across agents would save a little space and cost the one
 * property this table exists for.
 */
public final class JdbcPayloads implements Payloads {

  private static final String PUT =
      """
      INSERT INTO nessy_payload (agent_id, hash, content)
      VALUES (?, ?, ?)
          ON CONFLICT (agent_id, hash) DO NOTHING
      """;

  private static final String GET =
      "SELECT hash, content FROM nessy_payload WHERE agent_id = ? AND hash = ANY (?)";

  /**
   * What is written, rather than the bare list.
   *
   * <p>Jackson stamps a block with its type id only where the declared type is {@link Block}. A
   * list handed over as a list is serialised by each element's runtime class, the ids are left out,
   * and it reads back as nothing at all. Declaring the field is what keeps them.
   */
  private final JdbcClient jdbc;

  private final Codec<Payloads.Content> codec;
  private final AgentId agent;

  public JdbcPayloads(JdbcClient jdbc, CodecFactory codecs) {
    this(jdbc, codecs.create(Payloads.Content.class), null);
  }

  private JdbcPayloads(JdbcClient jdbc, Codec<Payloads.Content> codec, AgentId agent) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    this.codec = Objects.requireNonNull(codec, "codec must not be null");
    this.agent = agent;
  }

  @Override
  public Payloads forAgent(AgentId agent) {
    return new JdbcPayloads(jdbc, codec, Objects.requireNonNull(agent, "agent must not null"));
  }

  @Override
  public PayloadRef put(List<? extends Block> content) {
    byte[] encoded = codec.encode(new Payloads.Content(List.copyOf(content)));
    PayloadRef ref = Payloads.reference(encoded);
    jdbc.sql(PUT).params(scoped().value(), HexFormat.of().parseHex(ref.value()), encoded).update();
    return ref;
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
              found.put(ref, new Resolved.Found(codec.decode(rs.getBytes("content")).blocks()));
              return ref;
            })
        .list();
    return found;
  }

  private AgentId scoped() {
    if (agent == null) {
      throw new IllegalStateException("this store is not scoped to an agent; call forAgent first");
    }
    return agent;
  }
}
