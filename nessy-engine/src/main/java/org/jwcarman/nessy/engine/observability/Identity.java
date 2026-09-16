package org.jwcarman.nessy.engine.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnId;

/**
 * Whose span it is, said the same way on every span the engine opens.
 *
 * <p>The agent type is semconv's {@code gen_ai.agent.name}, low cardinality: a dashboard groups by
 * it. The agent id is {@code gen_ai.conversation.id}, high cardinality: in this engine an agent is
 * a conversation, and that is the attribute the conventions give for finding everything one
 * conversation did. The turn, where the span knows it, is {@code nessy.turn.id}.
 *
 * <p>Kept in the observation's context as well as on its tags, so a span opened underneath that
 * cannot be told whose it is -- a model call, which the provider is kept from knowing -- can read
 * it off the observation current when it starts.
 */
public record Identity(AgentType agentType, AgentId agentId) {

  public static final String AGENT_NAME = "gen_ai.agent.name";
  public static final String CONVERSATION_ID = "gen_ai.conversation.id";
  public static final String TURN_ID = "nessy.turn.id";

  public Identity {
    Objects.requireNonNull(agentType, "agentType must not be null");
    Objects.requireNonNull(agentId, "agentId must not be null");
  }

  /** The identity of the observation current on this thread, if it carries one. */
  public static Identity current(ObservationRegistry registry) {
    Observation current = registry.getCurrentObservation();
    return current == null ? null : current.getContext().get(Identity.class);
  }

  /** Tags the observation and leaves the identity in its context for what is opened beneath. */
  public void on(Observation observation, TurnId turn) {
    observation
        .lowCardinalityKeyValue(AGENT_NAME, agentType.value())
        .highCardinalityKeyValue(CONVERSATION_ID, agentId.value().toString());
    if (turn != null) {
      observation.highCardinalityKeyValue(TURN_ID, Long.toString(turn.value()));
    }
    observation.getContext().put(Identity.class, this);
  }
}
