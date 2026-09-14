package org.jwcarman.nessy.engine.tool;

import java.util.Optional;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;

/**
 * Resolves an address back into the call it names.
 *
 * <p>Exists because a {@code CallTool} effect carries where the call is rather than a copy of it.
 * The call was written down once, in the entry the model's request became, and that row is the only
 * copy -- so performing the effect starts by going and reading it.
 *
 * <p>A port, so the handler depends on being able to find a call rather than on a history store.
 * The same lookup serves an approver asking what it is being asked to approve.
 */
public interface ToolCalls {

  /**
   * The call at this address, or empty if the story does not have one.
   *
   * <p>Empty is a real answer, not an error to throw. It means an effect row and the story disagree
   * -- a partial restore, a hand-edited row -- and the caller is holding an obligation it still has
   * to discharge. Dying would leave the call outstanding forever.
   */
  Optional<ResolvedCall> find(AgentId agentId, Seq requestSeq, CallId callId);

  /**
   * A call, and where in the story it sits.
   *
   * <p>The turn comes back with it because an address names a position and the turn is part of what
   * that position means: it is what tells one turn's {@code "call_1"} from the next one's, and a
   * question parked for a person has to survive a restart still knowing which.
   */
  record ResolvedCall(TurnId turn, Block.ToolCall call) {}
}
