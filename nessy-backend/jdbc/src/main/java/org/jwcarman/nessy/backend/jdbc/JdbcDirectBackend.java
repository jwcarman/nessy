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

import java.util.Objects;
import javax.sql.DataSource;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.nessy.api.IdentityCodec;
import org.jwcarman.nessy.backend.DirectBackend;
import org.jwcarman.nessy.backend.chapter.Chapters;
import org.jwcarman.nessy.backend.event.AgentEvents;
import org.jwcarman.nessy.backend.lease.Leases;
import org.jwcarman.nessy.backend.lock.Locks;
import org.jwcarman.nessy.backend.payload.Payloads;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * A {@link DirectBackend} over one PostgreSQL {@link DataSource}: {@link JdbcAgentEvents}, {@link
 * JdbcPayloads}, {@link JdbcRowLocks}, {@link JdbcChapters} and {@link JdbcLeases}, built from it
 * once and held.
 *
 * <p><b>Shared and durable.</b> Every instance pointed at the same database sees the same rows, and
 * the rows outlive any one process -- which is what lets {@link JdbcRowLocks} exclude two instances
 * from the same agent, not merely two threads in one.
 *
 * <p>Nothing here creates {@code nessy_agent}, {@code nessy_agent_effect} or {@code
 * nessy_agent_backlog}: the direct door never names them, so a caller that only ever builds a
 * direct backend is not handed tables it will never write a row to.
 */
public final class JdbcDirectBackend implements DirectBackend {

  private final AgentEvents events;
  private final Payloads payloads;
  private final Locks locks;
  private final Chapters chapters;
  private final Leases leases;

  /**
   * For a caller that already coordinates its own transactions and builds its own codecs: the
   * factory is used as given for every store, payloads included, so a factory that already has a
   * storage transform is hashed after it: a payload's reference then depends on what the transform
   * writes. Use the constructor that takes the value codec and the transform apart when there is a
   * transform.
   */
  public JdbcDirectBackend(
      DataSource dataSource, PlatformTransactionManager transactions, CodecFactory codecs) {
    this(dataSource, transactions, codecs, codecs, IdentityCodec.INSTANCE);
  }

  /**
   * For a caller that has a storage transform and holds it apart from the value codec. Every store
   * is built over the two composed, as the other constructor's factory would be, except the
   * payloads, which get them apart so a payload's reference is a hash of its content before the
   * transform. With the other constructor, a factory that already includes a transform is hashed
   * after it.
   *
   * @param values the value codec, with no transform applied
   * @param transform the storage transform
   */
  public JdbcDirectBackend(
      DataSource dataSource,
      PlatformTransactionManager transactions,
      CodecFactory values,
      Codec<byte[]> transform) {
    this(
        dataSource,
        transactions,
        StorageCodecs.compose(
            Objects.requireNonNull(values, "values must not be null"),
            Objects.requireNonNull(transform, "transform must not be null")),
        values,
        transform);
  }

  private JdbcDirectBackend(
      DataSource dataSource,
      PlatformTransactionManager transactions,
      CodecFactory codecs,
      CodecFactory values,
      Codec<byte[]> transform) {
    Objects.requireNonNull(dataSource, "dataSource must not be null");
    Objects.requireNonNull(transactions, "transactions must not be null");
    Objects.requireNonNull(codecs, "codecs must not be null");
    JdbcClient jdbc = JdbcClient.create(dataSource);
    this.events = new JdbcAgentEvents(jdbc, codecs);
    this.payloads = new JdbcPayloads(jdbc, values, transform);
    this.locks = new JdbcRowLocks(dataSource, transactions);
    this.chapters = new JdbcChapters(jdbc, codecs);
    this.leases = new JdbcLeases(jdbc);
  }

  @Override
  public AgentEvents events() {
    return events;
  }

  @Override
  public Payloads payloads() {
    return payloads;
  }

  @Override
  public Locks locks() {
    return locks;
  }

  @Override
  public Chapters chapters() {
    return chapters;
  }

  @Override
  public Leases leases() {
    return leases;
  }
}
