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
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.random.RandomGenerator;
import javax.sql.DataSource;
import org.apache.pekko.actor.typed.ActorSystem;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelProvider;

/**
 * What an engine is built from.
 *
 * <p>A CONFIG, not a builder (design of record 2026-08-16 §1): fluent setters, no public {@code
 * build()}. It replaced a nine-argument constructor whose parameters could only be told apart by
 * counting, and which grew every time the engine needed one more thing.
 *
 * <p>One is required, because no default is honest: the {@link ModelProvider} the engine talks to.
 * Everything else has a default that works.
 *
 * <p><b>{@link #system} is a transitional field.</b> {@link EngineHarnessFactory} -- the engine
 * this config otherwise describes -- never reads it: an agent is a row now, not a cluster entity,
 * so nothing about the new engine needs an actor system. It stays here, and its setter stays
 * public, ONLY because {@code PekkoHarnessFactory} still reads it and is out of this task's scope
 * to touch -- deleting it, and every caller across the reactor that supplies {@code .system(...)},
 * is Task 11's one-piece removal of Pekko. Removing this field early would break that swap rather
 * than make it mechanical.
 */
public final class EngineConfig {

  private ActorSystem<?> system;
  private ModelProvider models;
  private DataSource dataSource;
  private int maxTokens = 4096;
  private Set<Capability> capabilities = new LinkedHashSet<>();
  private Executor blocking;
  private Clock clock = Clock.systemUTC();
  private ReplyTokens tokens;
  private Traces traces = Traces.noop();
  private RetryPolicy retryPolicy;
  private RandomGenerator random;
  private Duration maxDeferral = Duration.ofDays(30);

  /**
   * The actor system {@code PekkoHarnessFactory} shards its agents across. Transitional -- see the
   * class javadoc -- and unused by {@link EngineHarnessFactory}.
   */
  public EngineConfig system(ActorSystem<?> system) {
    this.system = Objects.requireNonNull(system, "system must not be null");
    return this;
  }

  /** Where models come from. Required. */
  public EngineConfig models(ModelProvider models) {
    this.models = Objects.requireNonNull(models, "models must not be null");
    return this;
  }

  /**
   * Where the engine keeps its own bookkeeping — claims and effects.
   *
   * <p>Unset, the engine builds an in-memory database of its own and initializes it, because that
   * database is ITS. A {@link DataSource} supplied here is never initialized uninvited: run {@code
   * Schemas.initialize} against it, or apply the shipped DDL however your operators prefer.
   *
   * <p>This is engine-internal storage, not application data. Nothing outside the engine reads a
   * claim, which is why the engine provides it rather than asking for an implementation.
   */
  public EngineConfig dataSource(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    return this;
  }

  /**
   * The longest answer to allow. Defaults to 4096 tokens.
   *
   * <p>Infrastructure rather than per-agent-kind configuration for now, because {@code
   * HarnessConfig} has no slot for it — worth revisiting when one kind of agent needs a different
   * ceiling from another.
   */
  public EngineConfig maxTokens(int maxTokens) {
    if (maxTokens < 1) {
      throw new IllegalArgumentException("maxTokens must be at least 1");
    }
    this.maxTokens = maxTokens;
    return this;
  }

  /** What the engine may ask a model for beyond text. Defaults to none. */
  public EngineConfig capabilities(Set<Capability> capabilities) {
    this.capabilities =
        new LinkedHashSet<>(Objects.requireNonNull(capabilities, "capabilities must not be null"));
    return this;
  }

  /**
   * Where blocking work runs — model calls above all.
   *
   * <p>Defaults to virtual threads, which is the right answer on this JVM: a blocked virtual thread
   * parks rather than holding a carrier, so a slow provider costs a stack instead of a thread.
   */
  public EngineConfig blocking(Executor blocking) {
    this.blocking = Objects.requireNonNull(blocking, "blocking must not be null");
    return this;
  }

  /** The clock everything time-shaped reads. Defaults to UTC. */
  public EngineConfig clock(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    return this;
  }

  /**
   * How a deferring tool's reply address is minted and read.
   *
   * <p>Defaults to an ephemeral key, which is correct for a process whose conversations do not
   * outlive it and wrong for anything that restarts while a call is still parked.
   */
  public EngineConfig replyTokens(ReplyTokens tokens) {
    this.tokens = Objects.requireNonNull(tokens, "tokens must not be null");
    return this;
  }

  /** Where spans go. Defaults to recording nothing. */
  public EngineConfig traces(Traces traces) {
    this.traces = Objects.requireNonNull(traces, "traces must not be null");
    return this;
  }

  /**
   * How many more times an obligation may be tried, and how long to wait before the next one, for
   * every effect that names no binding of its own -- {@code CallModel}, {@code TakeWork}, {@code
   * Remember.*} and {@code Release} (design of record 2026-09-04, Task 7). Per-binding policies for
   * {@code AskApprover} and {@code RunTool} are a later task's wiring; every effect uses this
   * default today.
   *
   * <p>Defaults to five attempts, doubling from one second, capped at five minutes.
   */
  public EngineConfig retryPolicy(RetryPolicy retryPolicy) {
    this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
    return this;
  }

  /**
   * The source of jitter {@link #retryPolicy} and the effect poller's own backoff read from.
   * Defaults to a generator seeded from the platform's own entropy source; supply one seeded
   * yourself only to make a test deterministic.
   */
  public EngineConfig random(RandomGenerator random) {
    this.random = Objects.requireNonNull(random, "random must not be null");
    return this;
  }

  /**
   * How far into the future a deferral -- {@code Awaited.Deferred(expiresAt)}, from a parked
   * approval or a parked tool alike -- may push its watchdog. {@code actionable_at} for a parked
   * effect IS the deferral's own deadline (design of record 2026-09-04, Task 7), so an unbounded
   * deferral is now an unbounded row nothing will ever look at again -- worse than before this
   * task, when a stray far-future deadline was merely inert rather than durably unreachable. A tool
   * that asks for longer than this gets this instead, and a warning naming both numbers, so the
   * clamp firing is visible rather than a silent surprise for whoever parked the call.
   *
   * <p>Defaults to 30 days -- long enough for a genuinely slow human process, short enough that a
   * misbehaving tool's {@code Instant.MAX} does not become a row this engine carries forever.
   */
  public EngineConfig maxDeferral(Duration maxDeferral) {
    Objects.requireNonNull(maxDeferral, "maxDeferral must not be null");
    if (maxDeferral.isNegative() || maxDeferral.isZero()) {
      throw new IllegalArgumentException("maxDeferral must be positive");
    }
    this.maxDeferral = maxDeferral;
    return this;
  }

  ActorSystem<?> system() {
    return Objects.requireNonNull(system, "an engine cannot be built without an actor system");
  }

  ModelProvider models() {
    return Objects.requireNonNull(
        models, "an engine cannot be built without a model provider to resolve its models");
  }

  /** Empty when the caller supplied none, which is how the engine knows to build its own. */
  Optional<DataSource> dataSource() {
    return Optional.ofNullable(dataSource);
  }

  int maxTokens() {
    return maxTokens;
  }

  Set<Capability> capabilities() {
    return Set.copyOf(capabilities);
  }

  Executor blocking() {
    return blocking == null ? Executors.newVirtualThreadPerTaskExecutor() : blocking;
  }

  Clock clock() {
    return clock;
  }

  ReplyTokens replyTokens() {
    return tokens == null ? ReplyTokens.ephemeral() : tokens;
  }

  Traces traces() {
    return traces;
  }

  RetryPolicy retryPolicy() {
    return retryPolicy == null
        ? RetryPolicy.exponential(Duration.ofSeconds(1), 2.0, Duration.ofMinutes(5), 5)
        : retryPolicy;
  }

  RandomGenerator random() {
    return random == null ? RandomGenerator.getDefault() : random;
  }

  Duration maxDeferral() {
    return maxDeferral;
  }
}
