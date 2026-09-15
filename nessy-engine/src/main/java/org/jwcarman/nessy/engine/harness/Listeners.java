package org.jwcarman.nessy.engine.harness;

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
 */
final class Listeners implements Narrator, AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(Listeners.class);

  // The engine's, read as they stand at each event -- a listener attached after this harness was
  // made still hears it -- and then this harness's own.
  private final List<AgentEventListener> engineWide;
  private final List<AgentEventListener> own;
  private final ExecutorService teller =
      Executors.newSingleThreadExecutor(Thread.ofVirtual().name("nessy-narration").factory());

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

  private static void tell(
      AgentEventListener listener, AgentType agentType, AgentId agentId, AgentEvent event) {
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
  }
}
