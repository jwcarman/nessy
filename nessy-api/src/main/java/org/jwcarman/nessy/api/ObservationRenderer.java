package org.jwcarman.nessy.api;

import java.util.List;
import org.jwcarman.nessy.api.block.Block;

/**
 * How an observation becomes something a model can read.
 *
 * <p>An observation is whatever the application's vocabulary says happened -- a sensor reading, a
 * webhook body, a typed message. The model only reads content. Rendering to blocks rather than to a
 * string is what lets an observation carry more than text later without a second door being cut for
 * it.
 *
 * <p><b>This is where {@code <O>} ends.</b> Everything before a renderer is parameterised by the
 * caller's own type; everything after it -- the story, the projection, the request, the result --
 * is not. That containment is why only the agent's own document needs a codec built for the
 * caller's type, and why every stored entry can share one.
 *
 * <p><b>A renderer never declines.</b> By the time one runs, the observation has been chosen: it
 * came out of the backlog and a turn is opening on it. A renderer that could refuse would be
 * deciding a stage too late, at the point where refusing breaks the turn lifecycle -- nothing
 * dispatches, so no turn ever ends, so anything waiting on that turn waits forever. Dropping,
 * merging and superseding are the backlog's business, and they happen before this.
 *
 * <p><b>It runs inside the fold, under the agent's row lock.</b> A renderer that formats is
 * invisible; one that fetches holds that lock across a network call. When rendering stops being
 * formatting it stops belonging here and becomes an effect with a state to wait in.
 *
 * @param <O> the observation type
 */
@FunctionalInterface
public interface ObservationRenderer<O> {

  List<Block.ObservationContent> render(O observation);

  /** For an observation that is already what the model should read. */
  static <O> ObservationRenderer<O> asString() {
    return observation -> List.of(new Block.Text(String.valueOf(observation)));
  }
}
