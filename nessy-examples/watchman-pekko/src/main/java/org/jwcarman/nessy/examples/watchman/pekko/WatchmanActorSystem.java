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
package org.jwcarman.nessy.examples.watchman.pekko;

import com.typesafe.config.Config;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.SpawnProtocol;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.jwcarman.nessy.engine.AgentActor;
import org.jwcarman.nessy.engine.AgentModel;
import org.jwcarman.nessy.engine.AgentRegistry;
import org.jwcarman.nessy.engine.AgentState;
import org.jwcarman.nessy.engine.AgentTools;
import org.jwcarman.nessy.engine.BlockingWork;
import org.jwcarman.nessy.engine.Coalescer;
import org.jwcarman.nessy.engine.PekkoHarness;
import org.jwcarman.nessy.engine.PekkoHarnessFactory;
import org.jwcarman.nessy.engine.Traces;
import org.jwcarman.nessy.spi.codec.CodecPipeline;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.substrate.Substrate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * The ActorSystem, owned by Spring. This class is our answer to the two integration questions that
 * actually bite.
 *
 * <p><b>1. SmartLifecycle, not {@code @Bean(destroyMethod = "terminate")}.</b> A destroy method
 * runs during bean destruction, which is late and — worse — unbounded: {@code
 * getWhenTerminated().join()} in a destroy method hangs the JVM forever if one actor is wedged, on
 * an application whose normal end is somebody pressing Ctrl-C. {@link SmartLifecycle} gives two
 * things a destroy method cannot: a PHASE, so shutdown order is stated rather than inferred, and
 * {@link #stop(Runnable)}, whose callback Spring waits on with {@code
 * spring.lifecycle.timeout-per-shutdown-phase} — so a wedged actor delays shutdown by that timeout
 * and then the JVM exits anyway.
 *
 * <p><b>2. The phase, and why this one.</b> Spring stops SmartLifecycle beans in DESCENDING phase
 * order, and the web server's lifecycle sits at {@code DEFAULT_PHASE - 1024}. Choosing a lower
 * number puts us AFTER it on the way down and BEFORE it on the way up, which is exactly the order a
 * long-running agent wants:
 *
 * <pre>
 *   start:  actors up  -&gt;  HTTP starts accepting        (never a request with no actor to serve it)
 *   stop:   HTTP drains  -&gt;  actors terminate           (never a turn killed under a live request)
 *   then:   Spring destroys beans, DataSource last      (persistence still works while we drain)
 * </pre>
 *
 * <p>The DataSource ordering matters and is easy to get wrong: Pekko's journal writes through its
 * own pool, but a turn that is mid-persist when the context tears down needs the database to still
 * be there. Terminating in {@code stop()} — before {@code destroyBeans()} — is what guarantees it.
 *
 * <p><b>3. Pekko's own shutdown hook is turned OFF</b> in {@code watchman-pekko.conf} ({@code
 * pekko.coordinated-shutdown.run-by-jvm-shutdown-hook = off}). By default Pekko installs a JVM
 * shutdown hook that terminates the ActorSystem, and Spring Boot installs one too. Two hooks, no
 * ordering between them: Pekko can pull the ActorSystem out from under Spring while Spring is still
 * stopping beans, and the symptom is dead-letter noise and lost work on a clean Ctrl-C. One owner
 * is the fix, and the owner is Spring.
 */
public final class WatchmanActorSystem implements SmartLifecycle {

  /** Below the web server's phase, so we start first and stop last. See the class javadoc. */
  public static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 2048;

  private static final Logger LOG = LoggerFactory.getLogger(WatchmanActorSystem.class);

  private final ActorSystem<SpawnProtocol.Command> system;
  private final PekkoHarness harness;
  private final ActorRef<AgentRegistry.Command> registry;
  private final BlockingWork blocking;
  private final Duration askTimeout;
  private final Traces traces;

  private volatile boolean running;

  /**
   * The shape the tests use: a scripted model and an explicit budget and coalescer.
   *
   * <p>Routed through the SAME factory as production — only the model's source differs — so these
   * tests exercise the real spawn path rather than a parallel wiring nobody ships.
   */
  public WatchmanActorSystem(
      Config config,
      AgentModel model,
      CommandRunner runner,
      Substrate substrate,
      Coalescer<String> coalescer,
      long budgetTokens,
      Traces traces,
      java.time.Clock clock,
      BlockingWork blocking,
      Duration approvalTerm,
      Duration askTimeout) {
    this.blocking = blocking;
    this.askTimeout = askTimeout;
    this.traces = traces;
    this.system = ActorSystem.create(SpawnProtocol.create(), "watchman", config);
    this.harness =
        new PekkoHarnessFactory(
                system,
                substrate,
                model,
                budgetTokens,
                WatchmanTools.boundTo(runner),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                traces,
                clock,
                blocking.executor(),
                CodecPipeline.none())
            .create(
                cfg ->
                    cfg.type("Watchman")
                        .systemPrompt(WatchmanPrompt.SYSTEM)
                        .coalescer(coalescer)
                        .renderer(WatchmanObservations.RENDERER)
                        .approvalTerm(approvalTerm));
    this.registry = harness.registry();
  }

  public WatchmanActorSystem(
      Config config,
      Substrate substrate,
      ModelProvider models,
      AgentTools tools,
      MeterRegistry meters,
      Traces traces,
      java.time.Clock clock,
      BlockingWork blocking,
      WatchmanProperties properties,
      Duration askTimeout,
      CodecPipeline pipeline) {
    this.blocking = blocking;
    this.askTimeout = askTimeout;
    this.traces = traces;
    // SpawnProtocol as the guardian, because Pekko refuses top-level spawns from outside a system
    // with a custom user guardian -- which is what lets a harness be handed a system it does not
    // own.
    this.system = ActorSystem.create(SpawnProtocol.create(), "watchman", config);
    this.harness =
        new PekkoHarnessFactory(
                system,
                substrate,
                models,
                properties.getModelId(),
                tools,
                meters,
                traces,
                clock,
                blocking.executor(),
                pipeline)
            .create(
                cfg ->
                    cfg.type("Watchman")
                        .systemPrompt(WatchmanPrompt.SYSTEM)
                        .modelName(properties.getModelId())
                        .maxTokens(properties.getMaxTokens())
                        .coalescer(WatchmanObservations.COALESCER)
                        .renderer(WatchmanObservations.RENDERER)
                        .approvalTerm(properties.getApprovalTerm()));
    this.registry = harness.registry();
  }

  /** Fire-and-forget to one agent. No round trip, no future: the cron does not wait for a round. */
  public void tell(String agentId, AgentActor.NessyMessage message) {
    registry.tell(new AgentRegistry.Envelope(agentId, message));
  }

  /** The trace context a caller at this boundary is standing in. */
  public Map<String, String> here() {
    return traces.capture();
  }

  /**
   * Answer a pending approval, and do not complete until the decision is DURABLE.
   *
   * <p>The returned stage is what the web layer must wait on; see {@link ApprovalsController}. The
   * agent replies only after {@code persist}, so completing this future is a promise that the
   * decision survived the process.
   */
  public CompletionStage<AgentActor.Ack> answerApproval(
      String agentId, String callId, boolean approved, String by, String note) {
    return AskPattern.<AgentRegistry.Command, AgentActor.Ack>ask(
        registry,
        replyTo ->
            new AgentRegistry.Envelope(
                agentId,
                new AgentActor.AnswerApproval(
                    callId, approved, by, note, replyTo, traces.capture())),
        askTimeout,
        system.scheduler());
  }

  /** What one agent looks like right now — used by the tests and the transcript page. */
  public CompletionStage<AgentState> inspect(String agentId) {
    return AskPattern.<AgentRegistry.Command, AgentState>ask(
        registry,
        replyTo ->
            new AgentRegistry.Envelope(agentId, new AgentActor.Inspect(replyTo, traces.capture())),
        askTimeout,
        system.scheduler());
  }

  public ActorSystem<SpawnProtocol.Command> raw() {
    return system;
  }

  // ------------------------------------------------------------------------------------------
  // Spring lifecycle
  // ------------------------------------------------------------------------------------------

  @Override
  public int getPhase() {
    return PHASE;
  }

  @Override
  public void start() {
    running = true;
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /**
   * Never called by Spring when {@link #stop(Runnable)} is implemented, but required by the API.
   */
  @Override
  public void stop() {
    stop(() -> {});
  }

  /**
   * Terminate, and tell Spring when it is done. Spring blocks here up to {@code
   * spring.lifecycle.timeout-per-shutdown-phase} and then carries on regardless, which is exactly
   * the behaviour a Ctrl-C wants: drain if you can, exit either way.
   */
  @Override
  public void stop(Runnable callback) {
    LOG.info("[watchman] terminating the actor system");
    running = false;
    system.terminate();
    system
        .getWhenTerminated()
        .whenComplete(
            (done, failure) -> {
              blocking.close();
              LOG.info("[watchman] actor system terminated");
              callback.run();
            });
  }
}
