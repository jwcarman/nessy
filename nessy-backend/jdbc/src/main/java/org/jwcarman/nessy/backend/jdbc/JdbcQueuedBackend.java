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
import org.jwcarman.codec.TypeRef;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.backend.QueuedBackend;
import org.jwcarman.nessy.backend.agent.Agents;
import org.jwcarman.nessy.backend.backlog.Backlogs;
import org.jwcarman.nessy.backend.effect.Effects;
import org.jwcarman.nessy.backend.event.AgentEvents;
import org.jwcarman.nessy.backend.lock.Locks;
import org.jwcarman.nessy.backend.payload.Payloads;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * A {@link QueuedBackend} over one PostgreSQL {@link DataSource}: {@link JdbcAgentEvents}, {@link
 * JdbcPayloads}, {@link JdbcRowLocks}, {@link JdbcAgents}, {@link JdbcEffects} and a {@link
 * JdbcBacklog} per agent, built from it once and held.
 *
 * <p><b>Shared and durable.</b> Every instance pointed at the same database sees the same rows and
 * the rows outlive any one process, which is what a queue answering hours or days later depends on.
 *
 * <p>{@link #backlogs} builds one {@link JdbcBacklog} per {@code (type, agent)} rather than holding
 * a single store: the store behind it is shared by every agent of every type, and a caller already
 * knows whose backlog it wants at the point it asks.
 */
public final class JdbcQueuedBackend implements QueuedBackend {

  private final JdbcClient jdbc;
  private final CodecFactory codecs;
  private final AgentEvents events;
  private final Payloads payloads;
  private final Locks locks;
  private final Agents agents;
  private final Effects effects;

  /**
   * For a plain, non-Spring caller with no {@link PlatformTransactionManager} of its own and no
   * {@link CodecFactory} of its own -- mints a {@link JdbcTransactionManager} over {@code
   * dataSource} the same way {@link JdbcRowLocks#JdbcRowLocks(DataSource)} does, and writes plain
   * Jackson bytes.
   */
  public JdbcQueuedBackend(DataSource dataSource) {
    this(
        dataSource,
        new JdbcTransactionManager(dataSource),
        new JacksonCodecFactory(JsonMapper.builder().build()));
  }

  /**
   * For a caller that already coordinates its own transactions and builds its own codecs -- a
   * Spring application hands in the context's {@link CodecFactory} bean, which is Jackson with
   * whatever storage transform the application declared already applied to every store this backend
   * builds, including a backlog's -- the one table the schema flags as holding raw user text, so it
   * is the one table that must never be the exception.
   */
  public JdbcQueuedBackend(
      DataSource dataSource, PlatformTransactionManager transactions, CodecFactory codecs) {
    Objects.requireNonNull(dataSource, "dataSource must not be null");
    Objects.requireNonNull(transactions, "transactions must not be null");
    this.codecs = Objects.requireNonNull(codecs, "codecs must not be null");
    this.jdbc = JdbcClient.create(dataSource);
    this.events = new JdbcAgentEvents(jdbc, codecs);
    this.payloads = new JdbcPayloads(jdbc, codecs);
    this.locks = new JdbcRowLocks(dataSource, transactions);
    this.agents = new JdbcAgents(jdbc);
    this.effects = new JdbcEffects(jdbc, codecs);
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
  public Agents agents() {
    return agents;
  }

  @Override
  public Effects effects() {
    return effects;
  }

  @Override
  public <I> Backlogs<I> backlogs(TypeRef<I> inputType) {
    Objects.requireNonNull(inputType, "inputType must not be null");
    Codec<I> codec = codecs.create(inputType);
    return (type, agent) -> new JdbcBacklog<>(jdbc, codec, agents, type, agent);
  }
}
