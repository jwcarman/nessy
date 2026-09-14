package org.jwcarman.nessy.engine.store;

import java.util.List;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.ObservationRenderer;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.engine.agent.Decision;
import org.jwcarman.nessy.engine.history.HistoryEntry;

/**
 * One agent type's story, as far as writing it is concerned.
 *
 * <p>Holds the two things the fold should not: which agent type these rows belong to, and how this
 * application's observations become something a model can read. So the fold hands over what it
 * decided and nothing else.
 *
 * <p><b>Write only.</b> Reading the story back is {@link TurnHistories}, deliberately a separate
 * port implemented by the same class: a fold may append, and must not be able to go looking. One
 * that could read its own history would be a fold whose result depended on something other than its
 * state and its event.
 *
 * <p><b>Per harness</b>, because {@link ObservationRenderer} is the application's and is typed to
 * its observations. Same shape as {@link AgentStateStore}: the caller-specific piece up here, the
 * shared row layer underneath.
 *
 * @param <O> the observation type, which ends at the renderer this holds
 */
public interface HistoryStore<O> {

  /**
   * Writes entries the fold formed itself, in order.
   *
   * <p>Everything that needs no renderer: an answer arrived as blocks, a failure and a refusal
   * carry no content at all.
   */
  List<Appended> append(AgentId agentId, List<HistoryEntry> entries);

  /**
   * Renders the observation a turn is opening on, and writes it down.
   *
   * <p>The one entry a fold cannot form. It knows which observation and at what seq; turning that
   * observation into content is this store's business, because the renderer is where {@code <O>}
   * ends and nothing downstream of here should know the caller's type.
   *
   * <p>Always written after everything in {@link #append}: a turn closes and the next opens in one
   * fold, in that order.
   */
  Appended open(AgentId agentId, Decision.Opening<O> opening);

  /** What one written entry turned out to be, kept only long enough to be logged. */
  record Appended(Seq seq, TurnId turnId, String type) {

    @Override
    public String toString() {
      return type + "@" + seq + " (turn " + turnId + ")";
    }
  }
}
