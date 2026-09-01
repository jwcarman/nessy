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
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
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
 * <p>Two are required, because no default is honest: the {@link ActorSystem} the engine runs on,
 * and the {@link ModelProvider} it talks to. Everything else has a default that works.
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

  /** The actor system the engine shards its agents across. Required. */
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
   * Where the engine keeps its own bookkeeping — claims and reminders.
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

  ActorSystem<?> system() {
    return required(system, "system");
  }

  ModelProvider models() {
    return required(models, "models");
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

  private static <T> T required(T value, String what) {
    if (value == null) {
      throw new IllegalStateException(what + " is required; an engine cannot be built without one");
    }
    return value;
  }
}
