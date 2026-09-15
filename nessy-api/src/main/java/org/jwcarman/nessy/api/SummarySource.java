package org.jwcarman.nessy.api;

import java.util.List;
import java.util.Optional;
import org.jwcarman.nessy.api.turn.Summary;

/**
 * Offers what stands in for turns that are no longer sent whole, asked afresh on every call.
 *
 * <p>The counterpart of {@link AmbientSource}, with the same shape and the opposite nature: ambient
 * is a view of the world right now and never written down, while a summary is derived from what was
 * said and names the turns it replaces. Both are asked on the way into a call; neither can reach
 * history.
 *
 * <p><b>The source decides which to return, and that is the whole design.</b> Every summary of
 * every prior episode, or only the ones relevant to the question in hand, or none -- the engine
 * takes the list as given and sends the story after the last of them. A gap between two returned
 * summaries is not filled; it is what leaving one out means.
 *
 * <p>How they are written, by whom and when is behind this interface and none of the engine's
 * business: a tool the model calls to close an episode, a background job folding the head of a long
 * story, a person editing a file. Each keeps its own store and answers from it here.
 */
@FunctionalInterface
public interface SummarySource {

  /** This agent's summaries, oldest first. Empty when there are none. */
  List<Summary> forAgent(AgentId agentId);

  /**
   * The last turn this source has a summary through -- whether or not it chose to show that
   * summary. The tail of verbatim turns begins after this, so a source that shows only the
   * summaries it finds relevant does not drag the whole summarised head back in as turns. By
   * default, the end of the last summary shown.
   */
  default Optional<TurnId> summarizedThrough(AgentId agentId) {
    List<Summary> shown = forAgent(agentId);
    return shown.isEmpty() ? Optional.empty() : Optional.of(shown.getLast().through());
  }

  static SummarySource none() {
    return _ -> List.of();
  }
}
