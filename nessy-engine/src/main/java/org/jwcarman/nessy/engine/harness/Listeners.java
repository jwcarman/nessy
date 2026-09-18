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
package org.jwcarman.nessy.engine.harness;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentEventListener;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.spi.narration.Narrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The narrator the engine talks to: every listener, told in turn, on a thread of this harness's
 * own.
 *
 * <p><b>Off the fold's thread.</b> A fold commits and moves on; what it announced is handed to one
 * virtual thread per harness, which tells the listeners in the order the events happened. A slow
 * listener delays the listeners behind it, never the agent -- and a listener that needs longer than
 * that wraps itself with {@link AgentEventListener#async()}.
 *
 * <p><b>Isolated.</b> A listener that throws is logged and the rest still hear. Nothing a listener
 * does can fail a turn.
 *
 * <p><b>In the trace.</b> Both hops -- onto this harness's telling thread, and from there onto the
 * thread of an {@link AgentEventListener.Async} listener -- carry the observation that was current
 * when the event was narrated, which is the turn's or the effect's. What a listener does in
 * response, a summary say, is then a child of what it responded to, however long after.
 */
final class Listeners implements Narrator, AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(Listeners.class);

  // The engine's, read as they stand at each event -- a listener attached after this harness was
  // made still hears it -- and then this harness's own.
  private final List<AgentEventListener> engineWide;
  private final List<AgentEventListener> own;
  private static final ContextSnapshotFactory SNAPSHOTS = ContextSnapshotFactory.builder().build();

  private final ExecutorService teller =
      propagating(
          Executors.newSingleThreadExecutor(Thread.ofVirtual().name("nessy-narration").factory()));

  // One thread per event per async listener, as async() promises; still in the trace.
  private final ExecutorService asyncTeller =
      propagating(
          Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("nessy-listener").factory()));

  private static ExecutorService propagating(ExecutorService executor) {
    return ContextExecutorService.wrap(executor, SNAPSHOTS::captureAll);
  }

  Listeners(List<AgentEventListener> engineWide, List<AgentEventListener> own) {
    this.engineWide = engineWide;
    this.own = List.copyOf(own);
  }

  @Override
  public void narrate(AgentType agentType, AgentId agentId, AgentEvent event) {
    teller.execute(() -> tell(agentType, agentId, event));
  }

  private void tell(AgentType agentType, AgentId agentId, AgentEvent event) {
    for (AgentEventListener listener : engineWide) {
      tell(listener, agentType, agentId, event);
    }
    for (AgentEventListener listener : own) {
      tell(listener, agentType, agentId, event);
    }
  }

  private void tell(
      AgentEventListener listener, AgentType agentType, AgentId agentId, AgentEvent event) {
    if (listener instanceof AgentEventListener.Async async) {
      // Its own thread, as it asked, but one of ours: the trace goes with it.
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
