package org.jwcarman.nessy.engine.inference;

import java.util.List;
import java.util.Optional;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.Ambient;
import org.jwcarman.nessy.api.AmbientSource;
import org.jwcarman.nessy.engine.store.TurnHistories;
import org.jwcarman.nessy.engine.store.TurnHistory;
import org.jwcarman.nessy.spi.inference.InferenceContext;

/**
 * Sends the most recent whole turns that fit, and nothing else.
 *
 * <p>The simplest assembler that works, and the right default: it needs no model call, no
 * background work and no second store, so an agent using it has nothing that can be stale, out of
 * date, or broken. What it gives up is everything older than the budget -- there is no summary
 * standing in for what fell off, so an agent asked about something from earlier in a long
 * conversation will answer as though it never happened.
 *
 * <p>The budget is spent from the newest turn backwards, and whole turns only. A window that opens
 * on a reply whose question was trimmed reads as nonsense to a model, so a turn that does not fit
 * is dropped entire rather than cut. The spending happens inside the query, so a long conversation
 * is never loaded in order to discard most of it.
 */
public class RecentTurnsContextAssembler implements InferenceContextAssembler {

  private final TurnHistories histories;
  private final int turns;
  private final List<AmbientSource> sources;

  public RecentTurnsContextAssembler(
      TurnHistories histories, int turns, List<AmbientSource> sources) {
    if (turns <= 0) {
      throw new IllegalArgumentException("turns must be positive");
    }
    this.histories = histories;
    this.turns = turns;
    this.sources = List.copyOf(sources);
  }

  @Override
  public InferenceContext assemble(InferenceInvocation invocation) {
    TurnHistory history = histories.forAgent(invocation.agentType(), invocation.agentId());
    // Handed over as turns. Flattening here would pick a wire shape on every adapter's
    // behalf, and they do not agree on one.
    return new InferenceContext(history.lastTurns(turns), ambientFor(invocation.agentId()));
  }

  /**
   * Asks every source, in the order they were bound.
   *
   * <p>Here rather than anywhere durable, because this runs once per call to the model, on the
   * dispatcher's thread and off the agent's row lock -- which is what lets a source read a table or
   * call a service, and what makes "the world as it stands now" mean now rather than whenever the
   * turn was written.
   *
   * <p>A source with nothing to say contributes nothing, rather than an empty section. The
   * difference matters: a labelled section with no content tells a model its notebook is empty,
   * which is a claim, where absence tells it nothing at all.
   */
  private List<Ambient> ambientFor(AgentId agentId) {
    return sources.stream()
        .map(source -> source.forAgent(agentId))
        .flatMap(Optional::stream)
        .toList();
  }
}
