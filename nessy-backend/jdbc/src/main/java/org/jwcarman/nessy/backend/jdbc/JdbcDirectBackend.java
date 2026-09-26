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
import org.jspecify.annotations.Nullable;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.backend.DirectBackend;
import org.jwcarman.nessy.backend.event.AgentEvents;
import org.jwcarman.nessy.backend.lock.Locks;
import org.jwcarman.nessy.backend.payload.Payloads;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * A {@link DirectBackend} over one PostgreSQL {@link DataSource}: {@link JdbcAgentEvents}, {@link
 * JdbcPayloads} and {@link JdbcRowLocks}, built from it once and held.
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

  /**
   * For a plain, non-Spring caller with no {@link PlatformTransactionManager} of its own and no
   * storage transform -- mints a {@link JdbcTransactionManager} over {@code dataSource} the same
   * way {@link JdbcRowLocks#JdbcRowLocks(DataSource)} does, and writes plain Jackson bytes.
   */
  public JdbcDirectBackend(DataSource dataSource) {
    this(dataSource, new JdbcTransactionManager(dataSource), null);
  }

  /**
   * For a caller that already coordinates its own transactions and may encrypt or compress what it
   * stores.
   *
   * @param storage what happens to a row's bytes after Jackson has written them and before Jackson
   *     reads them back -- compression, encryption, both. {@code null} means no transform beyond
   *     Jackson.
   */
  public JdbcDirectBackend(
      DataSource dataSource,
      PlatformTransactionManager transactions,
      @Nullable Codec<byte[]> storage) {
    Objects.requireNonNull(dataSource, "dataSource must not be null");
    Objects.requireNonNull(transactions, "transactions must not be null");
    CodecFactory jackson = new JacksonCodecFactory(JsonMapper.builder().build());
    CodecFactory codecs = storage == null ? jackson : StorageCodec.of(storage).after(jackson);
    JdbcClient jdbc = JdbcClient.create(dataSource);
    this.events = new JdbcAgentEvents(jdbc, codecs);
    this.payloads = new JdbcPayloads(jdbc, codecs);
    this.locks = new JdbcRowLocks(dataSource, transactions);
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
}
