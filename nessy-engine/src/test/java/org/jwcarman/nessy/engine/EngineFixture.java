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
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.AgentWork;
import org.jwcarman.nessy.api.IdentityCodec;
import org.jwcarman.nessy.api.NarrationListener;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.Replies;
import org.jwcarman.nessy.backend.QueuedBackend;
import org.jwcarman.nessy.backend.chapter.Chapters;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.event.AgentEvents;
import org.jwcarman.nessy.backend.jdbc.JdbcAgentEvents;
import org.jwcarman.nessy.backend.jdbc.JdbcPayloads;
import org.jwcarman.nessy.backend.jdbc.JdbcQueuedBackend;
import org.jwcarman.nessy.backend.jdbc.Schemas;
import org.jwcarman.nessy.backend.payload.Payloads;
import org.jwcarman.nessy.engine.core.AgentState;
import org.jwcarman.nessy.engine.harness.queued.DefaultQueuedHarnessFactory;
import org.jwcarman.nessy.engine.history.EventStreamHistory;
import org.jwcarman.nessy.engine.history.Transcript;
import org.jwcarman.nessy.engine.store.TurnHistories;
import org.jwcarman.nessy.engine.trace.TraceCarrier;
import org.jwcarman.nessy.engine.work.StoredAgentWork;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
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
  private final Chapters chapters;
  private final QueuedBackend backend;
  private final JdbcClient jdbc;

  public EngineFixture(InferenceProvider provider, NarrationListener listener) {
    this(provider, listener, ObservationRegistry.NOOP);
  }

  public EngineFixture(
      InferenceProvider provider, NarrationListener listener, ObservationRegistry observations) {
    this(
        provider,
        listener,
        observations,
        Optional.empty(),
        Optional.empty(),
        UnaryOperator.identity());
  }

  /** With the backend wrapped, so a test can make one of its operations fail on command. */
  public EngineFixture(
      InferenceProvider provider,
      NarrationListener listener,
      UnaryOperator<QueuedBackend> wrapped) {
    this(provider, listener, ObservationRegistry.NOOP, Optional.empty(), Optional.empty(), wrapped);
  }

  /** Tracing, with the context written by {@code carrier} rather than a momentary span. */
  public EngineFixture(
      InferenceProvider provider, ObservationRegistry observations, TraceCarrier carrier) {
    this(
        provider,
        NarrationListener.none(),
        observations,
        Optional.empty(),
        Optional.of(carrier),
        UnaryOperator.identity());
  }

  /** With something done to every stored byte, which the fixture's own reader must undo too. */
  public EngineFixture(InferenceProvider provider, Codec<byte[]> storage) {
    this(
        provider,
        NarrationListener.none(),
        ObservationRegistry.NOOP,
        Optional.of(storage),
        Optional.empty(),
        UnaryOperator.identity());
  }

  private EngineFixture(
      InferenceProvider provider,
      NarrationListener listener,
      ObservationRegistry observations,
      Optional<Codec<byte[]>> storage,
      Optional<TraceCarrier> carrier,
      UnaryOperator<QueuedBackend> wrapped) {
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
    CodecFactory codecs = storage.map(t -> andThen(jackson, t)).orElse(jackson);
    Codec<byte[]> transform = storage.orElse(IdentityCodec.INSTANCE);
    // Readers over the same tables the engine writes, so a test asserts on what was written down
    // rather than on the engine's own objects.
    this.events = new JdbcAgentEvents(jdbc, codecs);
    this.payloads = new JdbcPayloads(jdbc, jackson, transform);
    this.history =
        (type, id) ->
            new EventStreamHistory(events, new Transcript(payloads.forAgent(id)), type, id);

    JdbcQueuedBackend jdbcBackend =
        new JdbcQueuedBackend(
            dataSource, new JdbcTransactionManager(dataSource), jackson, transform);
    this.chapters = jdbcBackend.chapters();
    this.backend = jdbcBackend;
    this.harnesses =
        DefaultQueuedHarnessFactory.of(
            engine -> {
              engine
                  .backend(wrapped.apply(jdbcBackend))
                  .provider(ProviderId.of("test"), provider)
                  .inference(ProviderId.of("test"), InferenceOptions.of("a-model"))
                  .listener(listener)
                  .observations(observations);
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

  /** The closed chapters and their summaries, from the backend the harnesses write them to. */
  public Chapters chapters() {
    return chapters;
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
  public List<AgentEvent> story(AgentType type, AgentId agent) {
    return events.readAll(type, agent);
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
  public AgentState stateOf(AgentType type, AgentId agent) {
    return AgentState.idle(Seq.NONE).applyAll(story(type, agent));
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
   *
   * <p><b>Except for when.</b> A real engine stamps {@code startedAt} from its own clock, and this
   * factory has no way to know what that was, nor when the input arrived. Both instants here are
   * placeholders, so compare with {@link #ignoringWhenItStarted()} rather than by equality. The
   * label is the one an input of type {@code String} gets.
   */
  public AgentEvent.TurnStarted turnStarted(AgentId agent, long seq, String said) {
    Seq at = new Seq(seq);
    return new AgentEvent.TurnStarted(
        at,
        at.opensTurn(),
        ref(agent, List.of(new Block.Text(said))),
        "String",
        Instant.EPOCH,
        Instant.EPOCH);
  }

  /** Everything about a turn's opening except the moments it arrived and started. */
  public static RecursiveComparisonConfiguration ignoringWhenItStarted() {
    return RecursiveComparisonConfiguration.builder()
        .withIgnoredFields("startedAt", "arrivedAt")
        .build();
  }

  /**
   * The event as it would read if no request had been recorded with it. A real engine stores what
   * each model call's request was made of; this factory cannot say what that was, and the manifests
   * have tests of their own. Applies to the five model-call events and returns any other event
   * unchanged.
   */
  public static AgentEvent withoutManifest(AgentEvent event) {
    return switch (event) {
      case AgentEvent.InferenceAnswered e ->
          new AgentEvent.InferenceAnswered(
              e.seq(), e.turn(), e.answer(), e.truncated(), e.usage(), Optional.empty());
      case AgentEvent.InferenceRefused e ->
          new AgentEvent.InferenceRefused(
              e.seq(), e.turn(), e.category(), e.usage(), Optional.empty());
      case AgentEvent.InferenceFailed e ->
          new AgentEvent.InferenceFailed(
              e.seq(), e.turn(), e.failure(), e.usage(), Optional.empty());
      case AgentEvent.InferenceAttempted e ->
          new AgentEvent.InferenceAttempted(
              e.seq(), e.turn(), e.failure(), e.usage(), Optional.empty());
      case AgentEvent.ActionsRequested e ->
          new AgentEvent.ActionsRequested(
              e.seq(), e.turn(), e.request(), e.actions(), e.usage(), Optional.empty());
      default -> event;
    };
  }

  /**
   * The event recording an answer of {@code said}, as the fold would have written it, with no
   * request recorded: compare with the actual event passed through {@link
   * #withoutManifest(AgentEvent)}.
   */
  public AgentEvent.InferenceAnswered answered(AgentId agent, long seq, long turn, String said) {
    return new AgentEvent.InferenceAnswered(
        new Seq(seq),
        new TurnId(turn),
        ref(agent, List.of(new Block.Text(said))),
        false,
        Usage.unreported(),
        Optional.empty());
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

  /** The backend the engine runs on, for a test that stores something without telling an agent. */
  public QueuedBackend backend() {
    return backend;
  }

  /** What the factory says its agents are doing. */
  public AgentWork work() {
    return harnesses.work();
  }

  /**
   * The same read against the same stores with a clock a test controls, for the moment a parked
   * row's deadline passes without the dispatcher having a reason to claim it.
   */
  public AgentWork workAt(Clock clock) {
    return StoredAgentWork.queued(backend, clock);
  }

  @Override
  public void close() {
    harnesses.close();
    dataSource.close();
  }

  /**
   * {@code base}, with {@code transform} applied after it on the way in and before it on the way
   * out -- what a {@code StorageCodecConfigurer} does in the starter, done by hand here since this
   * fixture builds no Spring context.
   */
  private static CodecFactory andThen(CodecFactory base, Codec<byte[]> transform) {
    return new CodecFactory() {
      @Override
      public <T> Codec<T> create(TypeRef<T> type) {
        return base.create(type).andThen(transform);
      }
    };
  }
}
