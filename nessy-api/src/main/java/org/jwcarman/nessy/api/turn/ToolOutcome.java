package org.jwcarman.nessy.api.turn;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;

/**
 * What became of one call.
 *
 * <p>Three ways, and every one of them discharges the call. There is no fourth that leaves the
 * conversation sendable: a call with no outcome is rejected by every provider, so "still running"
 * is expressed by the outcome's absence from an {@link Exchange} rather than by a variant here.
 *
 * <p>Sealed, so an adapter switches exhaustively and cannot silently drop a denial into the same
 * shape as a failure without saying that is what it meant. On most wires the three do flatten to
 * one result block -- but that flattening is the adapter's to perform, and doing it here would
 * throw away the distinction before anyone could use it.
 */
// Named on the wire for the same reason TurnResult is: a Turn is written down whole.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = ToolOutcome.Succeeded.class, name = "succeeded"),
  @JsonSubTypes.Type(value = ToolOutcome.Failed.class, name = "failed"),
  @JsonSubTypes.Type(value = ToolOutcome.Denied.class, name = "denied")
})
public sealed interface ToolOutcome {

  /** The call this answers. Providers match results to calls by this id, never by position. */
  CallId callId();

  /** The tool ran and produced content. */
  record Succeeded(CallId callId, List<Block.ToolResultContent> blocks) implements ToolOutcome {

    public Succeeded {
      requireCallId(callId);
      Objects.requireNonNull(blocks, "blocks must not be null");
      if (blocks.isEmpty()) {
        throw new IllegalArgumentException("a result must have at least one block");
      }
      blocks = List.copyOf(blocks);
    }
  }

  /**
   * The tool ran and did not.
   *
   * <p>Discharges the call as firmly as success does -- the obligation is to answer, not to answer
   * well. The message is what the model reads, so it is content rather than diagnostics.
   */
  record Failed(CallId callId, String message) implements ToolOutcome {

    public Failed {
      requireCallId(callId);
      Objects.requireNonNull(message, "message must not be null");
    }
  }

  /**
   * The tool was never run, because an approver said no.
   *
   * <p>Kept apart from {@link Failed} because nothing happened in the world, which is a different
   * thing to tell a model than that something was attempted and went wrong.
   */
  record Denied(CallId callId, String reason) implements ToolOutcome {

    public Denied {
      requireCallId(callId);
      Objects.requireNonNull(reason, "reason must not be null");
    }
  }

  private static void requireCallId(CallId callId) {
    Objects.requireNonNull(callId, "callId must not be null");
  }
}
