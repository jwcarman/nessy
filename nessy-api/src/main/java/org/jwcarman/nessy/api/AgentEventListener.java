package org.jwcarman.nessy.api;

import java.util.Objects;
import java.util.function.Consumer;
import org.slf4j.LoggerFactory;

/**
 * Something that hears what agents do.
 *
 * <p>Told every {@link AgentEvent} about every agent of the harnesses it is attached to, after the
 * fold that produced it has committed. Best-effort by contract: a listener that throws is logged
 * and the others still hear; nothing a listener does can fail a turn, and nothing is replayed to a
 * listener that was not there. Work that must not be missed reads the story, which is durable.
 *
 * <p>Attach as many as you like -- to a harness with {@code HarnessConfig.listener(...)}, or to
 * every harness of an engine. A page's stream, a console, a summariser and a board are all one of
 * these.
 */
@FunctionalInterface
public interface AgentEventListener {

  void on(AgentType agentType, AgentId agentId, AgentEvent event);

  /**
   * This listener, told on a virtual thread of its own for every event, so however long it takes --
   * a model call, a slow write -- nothing else waits for it. Order across events is not kept: two
   * events may be handled at once, and the later may be handled first. Right for work that reads
   * the story rather than the event, such as a summariser; wrong for a stream a person is reading.
   */
  default AgentEventListener async() {
    AgentEventListener self = this;
    return (agentType, agentId, event) ->
        Thread.ofVirtual()
            .name("nessy-listener")
            .start(
                () -> {
                  try {
                    self.on(agentType, agentId, event);
                  } catch (RuntimeException e) {
                    // On a thread of its own there is nobody above to catch this: the engine's
                    // isolation ended when the hand-off did. Logged as the engine would have.
                    LoggerFactory.getLogger(AgentEventListener.class)
                        .warn(
                            "[{}] agent {}: a listener threw on {}; carrying on",
                            agentType.value(),
                            agentId.value(),
                            event.getClass().getSimpleName(),
                            e);
                  }
                });
  }

  /** Hears nothing. */
  static AgentEventListener none() {
    return (_, _, _) -> {};
  }

  /**
   * A listener that reacts to the kinds of event it names and ignores the rest:
   *
   * <pre>{@code
   * AgentEventListener.of(
   *     c -> c.agentType(CHAT).onTurnEnded((type, id, ended) -> summarize(id)));
   * }</pre>
   */
  static AgentEventListener of(Consumer<AgentEventListenerConfig> customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    AgentEventListenerConfig config = new AgentEventListenerConfig();
    customizer.accept(config);
    return config.build();
  }
}
