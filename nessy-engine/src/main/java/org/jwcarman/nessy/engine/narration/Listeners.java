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
package org.jwcarman.nessy.engine.narration;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.NarrationListener;
import org.jwcarman.nessy.api.Narrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The narrator the engine talks to: every listener, told in turn, on a thread of this harness's
 * own.
 *
 * <p><b>Off the fold's thread.</b> A fold commits and moves on; what it announced is handed to one
 * virtual thread per harness, which tells the listeners in the order the events happened. A slow
 * listener delays the listeners behind it, never the agent -- and a listener that needs longer than
 * that wraps itself with {@link NarrationListener#async()}.
 *
 * <p><b>Isolated.</b> A listener that throws is logged and the rest still hear. Nothing a listener
 * does can fail a turn.
 *
 * <p><b>In the trace.</b> Both hops -- onto this harness's telling thread, and from there onto the
 * thread of an {@link NarrationListener.Async} listener -- carry the input that was current when
 * the event was narrated, which is the turn's or the effect's. What a listener does in response, a
 * summary say, is then a child of what it responded to, however long after.
 */
public final class Listeners implements Narrator, AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(Listeners.class);

  // The engine's, read as they stand at each event -- a listener attached after this harness was
  // made still hears it -- and then this harness's own.
  private final List<NarrationListener> engineWide;
  private final List<NarrationListener> own;
  private static final ContextSnapshotFactory SNAPSHOTS = ContextSnapshotFactory.builder().build();

  private final ExecutorService teller =
      propagating(
          Executors.newSingleThreadExecutor(Thread.ofVirtual().name("nessy-narration").factory()));

  /**
   * One thread per event per async listener, as {@code async()} promises, and deliberately NOT in
   * the turn's trace.
   *
   * <p><b>This used to propagate, and the spans it produced lied about what a turn cost.</b> An
   * episode summary is a model call of its own: it began after its turn's span had closed and ran
   * 300ms past it, so a waterfall showed a 1.45s bar nested inside a 1.12s one. The longest bar in
   * a trace is the first thing anyone reads when asking why a request was slow, and that bar was
   * work nobody waited for. A child outliving its parent also breaks self-time and critical-path
   * arithmetic, which assume a child is contained.
   *
   * <p><b>What is lost is one click; what is kept is the answer.</b> These spans still carry {@link
   * org.jwcarman.nessy.engine.observability.Identity} -- {@code gen_ai.agent.name} and {@code
   * gen_ai.conversation.id} -- so "what else happened for this agent" is a query, and a better one
   * than parentage: it finds the work across every trace rather than only the turn you happened to
   * open. As roots these also become measurable on their own, which is the only way to ask whether
   * summaries are getting slower.
   *
   * <p>The synchronous {@link #teller} still propagates, and should: those listeners run inside the
   * turn, and their spans belong to it.
   */
  private final ExecutorService asyncTeller =
      Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("nessy-listener").factory());

  private static ExecutorService propagating(ExecutorService executor) {
    return ContextExecutorService.wrap(executor, SNAPSHOTS::captureAll);
  }

  public Listeners(List<NarrationListener> engineWide, List<NarrationListener> own) {
    this.engineWide = engineWide;
    this.own = List.copyOf(own);
  }

  @Override
  public boolean listening() {
    return !engineWide.isEmpty() || !own.isEmpty();
  }

  @Override
  public void narrate(AgentType agentType, AgentId agentId, Narration event) {
    teller.execute(() -> tell(agentType, agentId, event));
  }

  private void tell(AgentType agentType, AgentId agentId, Narration event) {
    for (NarrationListener listener : engineWide) {
      tell(listener, agentType, agentId, event);
    }
    for (NarrationListener listener : own) {
      tell(listener, agentType, agentId, event);
    }
  }

  private void tell(
      NarrationListener listener, AgentType agentType, AgentId agentId, Narration event) {
    if (listener instanceof NarrationListener.Async async) {
      // Its own thread, as it asked, and one of ours so shutdown can wait for it -- but the trace
      // does NOT go with it. See asyncTeller.
      asyncTeller.execute(() -> async.tell(agentType, agentId, event));
      return;
    }
    try {
      listener.on(agentType, agentId, event);
    } catch (RuntimeException e) {
      log.warn(
          "[{}] agent {}: a listener threw on {}; carrying on",
          agentType.value(),
          agentId.value(),
          event.getClass().getSimpleName(),
          e);
    }
  }

  /** Stops telling. What was queued is still told; nothing new is taken. */
  @Override
  public void close() {
    teller.shutdown();
    asyncTeller.shutdown();
  }
}
