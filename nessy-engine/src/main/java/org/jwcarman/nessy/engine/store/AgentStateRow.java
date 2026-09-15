package org.jwcarman.nessy.engine.store;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * The persisted form of an agent's state.
 *
 * <p>{@code version} is a real column, not a field inside the serialized payload. {@link
 * AgentStateRepository} moves it on by one at every save and refuses to write if somebody moved it
 * first, which is the optimistic check Spring Data used to perform. It is also where an event's
 * sequence number comes from -- the value read under the row lock is the seq the next event takes
 * -- which is why no code needs to count events to know where the log is.
 *
 * <p>Creating an agent is itself a revision, so {@code version} is one ahead of the event count: a
 * freshly created agent sits at 1 with no events, and its first event takes seq 1.
 */
public record AgentStateRow(
    UUID agentId,
    String agentType,
    long version,
    String stateType,
    byte[] payload,
    Instant updatedAt) {

  /** A row that has never been saved. Version zero is how the repository recognises an insert. */
  public static AgentStateRow initial(
      UUID agentId, String agentType, String stateType, byte[] payload, Instant at) {
    return new AgentStateRow(agentId, agentType, 0L, stateType, payload, at);
  }

  // The payload is compared by content, as a record of an array otherwise would not be.

  @Override
  public boolean equals(Object o) {
    return o
            instanceof
            AgentStateRow(
                UUID thatAgentId,
                String thatAgentType,
                long thatVersion,
                String thatStateType,
                byte[] thatPayload,
                Instant thatUpdatedAt)
        && version == thatVersion
        && Objects.equals(agentId, thatAgentId)
        && Objects.equals(agentType, thatAgentType)
        && Objects.equals(stateType, thatStateType)
        && Arrays.equals(payload, thatPayload)
        && Objects.equals(updatedAt, thatUpdatedAt);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        agentId, agentType, version, stateType, Arrays.hashCode(payload), updatedAt);
  }

  @Override
  public String toString() {
    return "AgentStateRow[agentId=%s, agentType=%s, version=%d, stateType=%s, payload=%d bytes, updatedAt=%s]"
        .formatted(
            agentId,
            agentType,
            version,
            stateType,
            payload == null ? 0 : payload.length,
            updatedAt);
  }

  /** The sequence number the next event appended for this agent will carry. */
  public long seq() {
    return version;
  }

  /**
   * This row carrying the state a fold produced. The version is passed through untouched:
   * incrementing it belongs to the save, and doing it here would both double-count and defeat the
   * optimistic check.
   */
  public AgentStateRow folded(String stateType, byte[] payload, Instant at) {
    return new AgentStateRow(agentId, agentType, version, stateType, payload, at);
  }

  /** This row at a given version. Used by the save to advance it. */
  AgentStateRow atVersion(long version) {
    return new AgentStateRow(agentId, agentType, version, stateType, payload, updatedAt);
  }
}
