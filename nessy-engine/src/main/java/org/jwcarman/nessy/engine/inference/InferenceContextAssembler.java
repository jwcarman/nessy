package org.jwcarman.nessy.engine.inference;

import org.jwcarman.nessy.spi.inference.InferenceContext;
import org.jwcarman.nessy.spi.inference.InferenceOptions;

/**
 * Builds everything the model is shown for one call. The extension point.
 *
 * <p>Handed the invocation rather than a window of history, because choosing what to read IS the
 * decision, not a detail beneath it. One that wants the recent tail asks for the recent tail; one
 * that summarises asks for a boundary and represents everything behind it some other way; one that
 * retrieves asks for ranges it has reason to believe are relevant. Handed a pre-built list it could
 * only ever discard, which is the least useful thing it can do.
 *
 * <p>It returns a context rather than a list of messages so that history is one ingredient among
 * several. A system prompt, ambient background assembled for this call and thrown away, and a tool
 * catalog once there is one all belong to whoever builds the box -- and they have no owner at all
 * if the only seam hands back messages.
 *
 * <p>It owns the token budget, because the budget is an assembly decision -- how much to spend, and
 * on what -- rather than a property of the agent or the model. Two assemblers over the same history
 * may legitimately spend differently. The model's own {@code maxTokens} is a different number in
 * the opposite direction and lives in {@link InferenceOptions}.
 *
 * <p>Everything it returns from the story must be a projection of what was written. One that
 * invents an exchange puts words in the model's mouth that no record holds, and the next turn will
 * read them back as history.
 */
@FunctionalInterface
public interface InferenceContextAssembler {

  /**
   * Builds the context for one call.
   *
   * @return what to send, in the order it should be sent
   */
  InferenceContext assemble(InferenceInvocation invocation);
}
