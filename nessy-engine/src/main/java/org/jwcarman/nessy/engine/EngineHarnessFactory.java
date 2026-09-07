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

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;
import javax.sql.DataSource;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.HarnessConfig;
import org.jwcarman.nessy.api.HarnessFactory;
import org.jwcarman.nessy.api.memory.Memory;
import org.jwcarman.nessy.api.message.UserMessage;
import org.jwcarman.nessy.spi.codec.Codecs;
import org.jwcarman.nessy.spi.memory.TranscriptMemory;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.store.Schemas;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Builds harnesses with no cluster underneath them.
 *
 * <p>Everything shared is given here, once: where state lives, how models are reached, what runs
 * blocking work. Each {@link #createHarness} call adds only what makes one kind of agent different
 * from another — which is why {@link HarnessConfig} names no infrastructure at all.
 *
 * <p><b>No sharding, no passivation, no entity system to borrow.</b> An agent is a row an agent id
 * names; any node may work it, and {@link EffectPoller#pollOnce} is what makes durable obligations
 * progress rather than an actor mailbox. This factory owns exactly two things a cluster used to own
 * for it: a thread pool for driving turns, and the periodic loops in {@link #startSweeps} that
 * replace what an actor scheduler used to trigger.
 */
public final class EngineHarnessFactory implements HarnessFactory, AutoCloseable {

  /**
   * The default memory's budget, in characters.
   *
   * <p>Arbitrary, and chosen to be SAFE rather than optimal: roughly 25k tokens, which leaves room
   * for a system prompt, tool schemas and an answer inside every current model's window. An
   * application that knows its own shape supplies its own memory.
   */
  private static final int DEFAULT_MEMORY_CHARACTERS = 100_000;

  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(EngineHarnessFactory.class);

  /** How long a RUNNING effect row may go unattended before {@link EffectPoller} retries it. */
  private static final Duration WATCHDOG = Duration.ofMinutes(5);

  /** How often the stall sweep looks for an agent left mid-turn by a node that vanished. */
  private static final Duration REAP_INTERVAL = Duration.ofSeconds(30);

  /** How long an agent may sit busy with no progress before the stall sweep recovers it. */
  private static final Duration STALL_AFTER = Duration.ofMinutes(5);

  /** How many stalled agents one stall-sweep pass recovers at once. */
  private static final int REAP_BATCH = 100;

  /** The most obligations {@link EffectPoller#pollOnce} attempts in one pass. */
  private static final int POLL_BATCH = 50;

  /** The shortest wait between poll passes, taken the instant a pass finds work. */
  private static final Duration POLL_FLOOR = Duration.ofMillis(100);

  /** The longest wait an idle poller backs off to. */
  private static final Duration POLL_CEILING = Duration.ofSeconds(30);

  /** How much an idle pass stretches the poll interval by. */
  private static final double POLL_BACKOFF_MULTIPLIER = 2.0;

  /** How far a poll interval may jitter from its unjittered value, as a fraction of it. */
  private static final double POLL_JITTER_FRACTION = 0.2;

  private final DataSource dataSource;
  private final ModelProvider models;
  private final int maxTokens;
  private final Set<Capability> capabilities;
  private final Executor blocking;
  private final Clock clock;
  private final ReplyTokens tokens;
  private final Traces traces;
  private final Claims claims;
  private final RetryPolicy retryPolicy;
  private final RandomGenerator random;
  private final Duration maxDeferral;
  private final ExecutorService threads;
  private final Narration narration;
  private final Replies replies;
  private final List<Sweeps> sweeps = new CopyOnWriteArrayList<>();
  private final Map<AgentType, AgentRuntime> runtimes = new ConcurrentHashMap<>();

  /**
   * Builds an engine from {@code customizer}'s settings.
   *
   * <pre>{@code
   * new EngineHarnessFactory(engine -> engine.models(models).dataSource(ds));
   * }</pre>
   *
   * @throws IllegalStateException if a required setting was not supplied
   */
  public EngineHarnessFactory(Consumer<EngineConfig> customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    EngineConfig config = new EngineConfig();
    customizer.accept(config);
    this.models = config.models();
    this.maxTokens = config.maxTokens();
    this.capabilities = config.capabilities();
    this.blocking = config.blocking();
    this.clock = config.clock();
    this.tokens = config.replyTokens();
    this.traces = config.traces();
    this.retryPolicy = config.retryPolicy();
    this.random = config.random();
    this.maxDeferral = config.maxDeferral();
    this.dataSource = config.dataSource().orElseGet(EngineHarnessFactory::ownDatabase);
    this.claims = new Claims(this.dataSource);
    this.threads =
        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("nessy-", 0).factory());
    this.narration = new Narration();
    this.replies =
        new Replies(this.tokens, this.traces, this.claims, new EffectStore(this.dataSource));
  }

  /**
   * The engine's own database, when an application supplied none.
   *
   * <p>The engine needs claims and effect rows, so the engine provides them: nothing outside reads
   * either, so neither is an extension point and neither should be something an application has to
   * wire. In memory here, and initialized because it is OURS — a {@link DataSource} an application
   * supplies is never touched uninvited, which is the whole reason our DDL is named {@code
   * nessy-schema.sql} rather than {@code schema.sql}.
   */
  private static DataSource ownDatabase() {
    EmbeddedDatabase database =
        new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true)
            .build();
    Schemas.initialize(database);
    return database;
  }

  @Override
  public <O> Harness<O> createHarness(
      Class<O> observationType, Consumer<HarnessConfig<O>> configurer) {
    Objects.requireNonNull(observationType, "observationType must not be null");
    Objects.requireNonNull(configurer, "configurer must not be null");

    EngineHarnessConfig<O> config = new EngineHarnessConfig<>();
    configurer.accept(config);
    AgentType type = config.agentType();

    // The one moment O is statically known. It is handed to the BACKLOG STORE and goes no further:
    // the store owns the codec, the renderer and the coalescer, so nothing above it is generic.
    Codec<O> codec = Codecs.factory().create(observationType);

    Memory memory = memoryFor(config, type);
    Model model = models.model(config.modelId());
    ToolBindings bindings = new ToolBindings(config.toolBindings(), EngineMapper.INSTANCE);

    BacklogStore<O> backlog =
        new BacklogStore<>(
            dataSource,
            claims,
            codec,
            JsonCodec.of(EngineMapper.INSTANCE, UserMessage.class),
            config.observationRenderer(),
            config.backlogCoalescer(),
            clock);

    AgentStore store = new AgentStore(dataSource);
    EffectStore effects = new EffectStore(dataSource);
    TransactionTemplate transactions =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    Transition transition = new Transition(type, store, effects, transactions);

    // Runtime and effectWorker reference each other -- the runtime is the Performer's caller and
    // the dispatcher effectWorker calls through is the runtime itself -- so the cycle is broken
    // with an explicit holder rather than closing over a non-final local.
    AtomicReference<AgentRuntime> runtimeRef = new AtomicReference<>();
    Dispatcher dispatcher =
        (agentId, input, completing, observability) ->
            runtimeRef.get().dispatch(agentId, input, completing, observability);

    EffectWorker effectWorker =
        new EffectWorker(
            new EffectWorker.Dependencies(
                type,
                memory,
                model,
                config.prompt(),
                maxTokens,
                bindings,
                capabilities,
                narration::narratorFor,
                claims,
                tokens,
                blocking,
                traces,
                backlog,
                effects,
                dispatcher,
                store,
                retryPolicy,
                random,
                maxDeferral));

    AgentRuntime runtime =
        new AgentRuntime(type, transition, effectWorker::perform, threads, traces);
    runtimeRef.set(runtime);
    runtimes.put(type, runtime);
    replies.serving(type, runtime);

    startSweeps(type, store, effects, runtime);

    return new LocalHarness<>(type, backlog, runtime, narration);
  }

  /**
   * Two loops, both plain virtual threads and neither a singleton.
   *
   * <p>The poller finds obligations whose {@code actionable_at} has passed -- a retry that is due,
   * a park term that lapsed, an attempt whose watchdog expired -- and runs them. There is no reaper
   * beside it, because there is nothing to reap: an unfinished obligation is not excluded from
   * anyone, so becoming due again IS its recovery.
   *
   * <p>The stall sweep wakes agents left mid-turn by a node that vanished -- not idle ones, which
   * the observe that gave them work already woke, and which take again at the end of every turn.
   */
  private void startSweeps(
      AgentType type, AgentStore store, EffectStore effects, AgentRuntime runtime) {
    PollSchedule schedule =
        new PollSchedule(
            POLL_FLOOR, POLL_CEILING, POLL_BACKOFF_MULTIPLIER, POLL_JITTER_FRACTION, random);
    EffectPoller poller =
        new EffectPoller(type, effects, runtime, schedule, threads, POLL_BATCH, WATCHDOG);
    sweeps.add(started(new Sweeps(() -> schedule.next(poller.pollOnce()))));
    sweeps.add(
        started(
            new Sweeps(
                () -> {
                  store
                      .stalled(type, clock.instant().minus(STALL_AFTER), REAP_BATCH)
                      .forEach(runtime::recover);
                  return REAP_INTERVAL;
                })));
  }

  private static Sweeps started(Sweeps sweeps) {
    sweeps.start();
    return sweeps;
  }

  /** Stops every loop and the thread pool. Idempotent. */
  public void close() {
    sweeps.forEach(Sweeps::close);
    sweeps.clear();
    threads.shutdownNow();
  }

  /** Where the outside world answers calls parked by any agent this factory serves. */
  public Replies replies() {
    return replies;
  }

  /**
   * What the application asked for, or a default that keeps working.
   *
   * <p>The default is announced rather than assumed. It is chosen so an agent does not eventually
   * stop — not because it is a good memory — and the difference matters enough to say out loud
   * once, the same way an in-memory database does.
   */
  private <O> Memory memoryFor(EngineHarnessConfig<O> config, AgentType type) {
    Memory supplied = config.memory();
    if (supplied != null) {
      return supplied;
    }
    LOG.warn(
        "NESSY IS USING THE DEFAULT MEMORY for agent type '{}': the newest ~{} characters of"
            + " history and nothing else — no summarization, no retrieval, and the oldest turns are"
            + " simply forgotten. Supply one via HarnessConfig.memory for anything that is not a"
            + " demo.",
        type.name(),
        DEFAULT_MEMORY_CHARACTERS);
    return TranscriptMemory.recent(dataSource, type, DEFAULT_MEMORY_CHARACTERS);
  }

  /**
   * The plumbing a token-holding caller needs to answer a call from outside — engine-internal, and
   * package-visible only so a test in this package can drive the same path {@link #replies()} uses.
   * Not exposed through {@code Harness} itself: it deliberately names no door for returning
   * approvals or tool results (design of record, {@code Harness} javadoc); {@link #replies()} is
   * the separate door this factory does build.
   */
  Claims claims() {
    return claims;
  }

  ReplyTokens replyTokens() {
    return tokens;
  }

  AgentRuntime runtimeFor(AgentType type) {
    return runtimes.get(type);
  }
}
