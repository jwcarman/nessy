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
package org.jwcarman.nessy.engine;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.NarrationListener;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.tool.Replies;
import org.jwcarman.nessy.engine.core.AgentEvent;
import org.jwcarman.nessy.engine.core.AgentEvents;
import org.jwcarman.nessy.engine.core.AgentState;
import org.jwcarman.nessy.engine.harness.queued.DefaultQueuedHarnessFactory;
import org.jwcarman.nessy.engine.history.EventStreamHistory;
import org.jwcarman.nessy.engine.history.Transcript;
import org.jwcarman.nessy.engine.jdbc.JdbcAgentEvents;
import org.jwcarman.nessy.engine.jdbc.JdbcPayloads;
import org.jwcarman.nessy.engine.store.StorageCodec;
import org.jwcarman.nessy.engine.store.TurnHistories;
import org.jwcarman.nessy.engine.trace.TraceCarrier;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.Seq;
import org.jwcarman.nessy.inference.TurnId;
import org.jwcarman.nessy.inference.block.Block;
import org.jwcarman.nessy.spi.store.Payloads;
import org.jwcarman.nessy.spi.store.Schemas;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * A whole engine, wired by hand against a real PostgreSQL.
 *
 * <p><b>No Spring Boot, deliberately.</b> The engine's own POM claims it is a library — that Spring
 * is used for {@code JdbcClient} and {@code TaskScheduler} and nothing else, and that no {@code
 * ApplicationContext} is required to run one. This class is the only proof of that claim: if the
 * engine ever grows a dependency on being in a container, this stops compiling.
 *
 * <p><b>Real PostgreSQL, not H2.</b> The queries this engine rests on are PostgreSQL's: {@code FOR
 * UPDATE SKIP LOCKED} for claiming work, {@code LEAST} for capping a deadline, and parameters that
 * PostgreSQL refuses as a bare {@code Instant} while H2 accepts them happily. A test on H2 would
 * pass against exactly the bugs that matter.
 *
 * <p>One container for the whole class, and agents are addressed by fresh random ids, so tests do
 * not have to clean up after each other.
 */
public final class EngineFixture implements AutoCloseable {

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  static {
    POSTGRES.start();
  }

  private final HikariDataSource dataSource;
  private final DefaultQueuedHarnessFactory harnesses;
  private final TurnHistories history;
  private final AgentEvents events;
  private final Payloads payloads;
  private final JdbcClient jdbc;

  public EngineFixture(InferenceProvider provider, NarrationListener listener) {
    this(provider, listener, ObservationRegistry.NOOP);
  }

  public EngineFixture(
      InferenceProvider provider, NarrationListener listener, ObservationRegistry observations) {
    this(provider, listener, observations, Optional.empty(), Optional.empty());
  }

  /** Tracing, with the context written by {@code carrier} rather than a momentary span. */
  public EngineFixture(
      InferenceProvider provider, ObservationRegistry observations, TraceCarrier carrier) {
    this(provider, NarrationListener.none(), observations, Optional.empty(), Optional.of(carrier));
  }

  /** With something done to every stored byte, which the fixture's own reader must undo too. */
  public EngineFixture(InferenceProvider provider, Codec<byte[]> storage) {
    this(
        provider,
        NarrationListener.none(),
        ObservationRegistry.NOOP,
        Optional.of(storage),
        Optional.empty());
  }

  private EngineFixture(
      InferenceProvider provider,
      NarrationListener listener,
      ObservationRegistry observations,
      Optional<Codec<byte[]>> storage,
      Optional<TraceCarrier> carrier) {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(POSTGRES.getJdbcUrl());
    config.setUsername(POSTGRES.getUsername());
    config.setPassword(POSTGRES.getPassword());
    this.dataSource = new HikariDataSource(config);

    // Opt-in, and the whole point of the convention: an application says when its schema is
    // created rather than having a framework run a file named schema.sql behind its back.
    Schemas.initialize(dataSource);

    // Held for assertions only. The engine builds its own from the same DataSource; these are
    // stateless readers over the same tables, and a test that reached into the engine's would be
    // asserting on its internals rather than on what it wrote down.
    this.jdbc = JdbcClient.create(dataSource);
    CodecFactory jackson = new JacksonCodecFactory(JsonMapper.builder().build());
    CodecFactory codecs = storage.map(t -> StorageCodec.of(t).after(jackson)).orElse(jackson);
    // Readers over the same tables the engine writes, so a test asserts on what was written down
    // rather than on the engine's own objects.
    this.events = new JdbcAgentEvents(jdbc, codecs);
    this.payloads = new JdbcPayloads(jdbc, codecs);
    this.history =
        (type, id) -> new EventStreamHistory(events, new Transcript(payloads.forAgent(id)), id);

    this.harnesses =
        DefaultQueuedHarnessFactory.of(
            engine -> {
              engine
                  .dataSource(dataSource)
                  .inference(provider, InferenceOptions.of("a-model"))
                  .listener(listener)
                  .observations(observations);
              storage.ifPresent(engine::storage);
              carrier.ifPresent(engine::traceCarrier);
            });
  }

  public EngineFixture(InferenceProvider provider) {
    this(provider, NarrationListener.none());
  }

  public DefaultQueuedHarnessFactory harnesses() {
    return harnesses;
  }

  public TurnHistories history() {
    return history;
  }

  /** The events themselves, for a test asserting on the story rather than on the turns. */
  public AgentEvents events() {
    return events;
  }

  public Payloads payloads() {
    return payloads;
  }

  /**
   * One agent's whole story, oldest first.
   *
   * <p>What a test asserting on what happened wants, and the replacement for reading entries from a
   * turn: the events ARE the story now, and a turn is a reading of them.
   */
  public List<AgentEvent> story(AgentId agent) {
    return events.readFrom(agent, Seq.NONE);
  }

  /**
   * The content a reference stands for.
   *
   * <p>Events carry references rather than blocks, so a test that asserts on words has to go and
   * get them. A dangling reference fails the test where it is dereferenced rather than returning
   * something empty, because in this engine a missing payload is always a fault.
   */
  public List<Block> content(AgentId agent, PayloadRef ref) {
    return switch (payloads.forAgent(agent).get(ref)) {
      case Payloads.Resolved.Found(List<Block> blocks) -> blocks;
      case Payloads.Resolved.Missing() -> throw new AssertionError("no payload stored at " + ref);
    };
  }

  /**
   * What state this agent is in, by replaying what happened to it.
   *
   * <p>There is no state column to read any more, and that is the design rather than an omission:
   * the events are the truth and a state is what replaying them produces. So this is not a
   * convenience over a stored answer -- it is the same thing the engine itself does to find out.
   */
  public AgentState stateOf(AgentId agent) {
    return AgentState.idle(Seq.NONE).applyAll(story(agent));
  }

  /**
   * The reference this content has, for building an event a test expects to find.
   *
   * <p>Safe to call when the engine has already stored the same content: a reference IS the hash of
   * what it points at, so putting it a second time yields the same reference and the same single
   * row. That is what lets a test say what it expects without having watched it being written.
   */
  public PayloadRef ref(AgentId agent, List<? extends Block> content) {
    return payloads.forAgent(agent).put(content);
  }

  /**
   * The event that opens a turn on {@code said}, as the fold would have written it.
   *
   * <p>The turn is derived from the position rather than passed, because an input's own seq is the
   * turn it opens -- the same rule {@code AgentState} applies.
   */
  public AgentEvent.TurnStarted turnStarted(AgentId agent, long seq, String said) {
    Seq at = new Seq(seq);
    return new AgentEvent.TurnStarted(
        at, at.opensTurn(), ref(agent, List.of(new Block.Text(said))));
  }

  /** The event recording an answer of {@code said}, as the fold would have written it. */
  public AgentEvent.InferenceAnswered answered(AgentId agent, long seq, long turn, String said) {
    return new AgentEvent.InferenceAnswered(
        new Seq(seq), new TurnId(turn), ref(agent, List.of(new Block.Text(said))));
  }

  /** The text of what a reference stands for, joined, for the common assertion. */
  public String text(AgentId agent, PayloadRef ref) {
    return content(agent, ref).stream()
        .filter(Block.Text.class::isInstance)
        .map(Block.Text.class::cast)
        .map(Block.Text::text)
        .collect(Collectors.joining());
  }

  public javax.sql.DataSource dataSource() {
    return dataSource;
  }

  public JdbcClient jdbc() {
    return jdbc;
  }

  public Replies replies() {
    return harnesses.replies();
  }

  @Override
  public void close() {
    harnesses.close();
    dataSource.close();
  }
}
