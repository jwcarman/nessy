package org.jwcarman.nessy.engine.inference;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.Ambient;
import org.jwcarman.nessy.api.AmbientSource;
import org.jwcarman.nessy.api.SummarySource;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.turn.Summary;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.engine.store.TurnHistories;
import org.jwcarman.nessy.engine.store.TurnHistory;
import org.jwcarman.nessy.spi.inference.InferenceContext;

/**
 * Builds what the model is sent: summaries, then the tail, then background.
 *
 * <p><b>The tail's floor is the last summary.</b> Whatever the sources return is taken as given and
 * sent first, in order; the story after the last one's {@code through} is the tail, capped at
 * {@code maxTail} newest turns. With no summaries the tail is the whole story, capped the same way,
 * which is the window every agent had before summaries existed.
 *
 * <p>A gap between two summaries is not filled. Filling it would defeat a source that returns only
 * the relevant ones -- leaving out eight episodes' summaries would load eight episodes' turns. What
 * a source leaves out is simply not sent.
 *
 * <p>Two things are refused rather than sent. Summaries that overlap, because a source handing back
 * the same turns twice in two forms is confused rather than selective; and a summary that reaches
 * the turn in flight, because that is a source summarising the question before it has been answered
 * -- detected as an empty tail, since the turn being answered is always the newest one and is
 * always in the story by the time this runs.
 */
public class ContextAssembler implements InferenceContextAssembler {

  private final TurnHistories histories;
  private final List<SummarySource> summaries;
  private final int maxTail;
  private final List<AmbientSource> ambient;

  public ContextAssembler(
      TurnHistories histories,
      List<SummarySource> summaries,
      int maxTail,
      List<AmbientSource> ambient) {
    if (maxTail <= 0) {
      throw new IllegalArgumentException("maxTail must be positive");
    }
    this.histories = histories;
    this.summaries = List.copyOf(summaries);
    this.maxTail = maxTail;
    this.ambient = List.copyOf(ambient);
  }

  @Override
  public InferenceContext assemble(InferenceInvocation invocation) {
    TurnHistory history = histories.forAgent(invocation.agentType(), invocation.agentId());
    List<Summary> covered = summariesFor(invocation.agentId());
    // Handed over as turns. Flattening here would pick a wire shape on every adapter's behalf,
    // and they do not agree on one.
    return new InferenceContext(covered, tail(history, covered), ambientFor(invocation.agentId()));
  }

  private List<Summary> summariesFor(AgentId agentId) {
    List<Summary> gathered = new ArrayList<>();
    for (SummarySource source : summaries) {
      gathered.addAll(source.forAgent(agentId));
    }
    for (int i = 1; i < gathered.size(); i++) {
      Summary before = gathered.get(i - 1);
      Summary after = gathered.get(i);
      if (after.from().value() <= before.through().value()) {
        throw new IllegalStateException(
            "summaries overlap or are out of order: %s..%s then %s..%s"
                .formatted(before.from(), before.through(), after.from(), after.through()));
      }
    }
    return List.copyOf(gathered);
  }

  private List<Turn> tail(TurnHistory history, List<Summary> covered) {
    if (covered.isEmpty()) {
      return history.lastTurns(maxTail);
    }
    TurnId through = covered.getLast().through();
    List<Turn> after = history.turnsAfter(through);
    if (after.isEmpty()) {
      throw new IllegalStateException(
          "a summary reaches the turn being answered: nothing is left after turn " + through);
    }
    // Capped in memory for now: the common case is a tail well under the cap, and a summary
    // source that lets the tail grow past it is the thing to fix rather than the query.
    return after.size() <= maxTail
        ? after
        : List.copyOf(after.subList(after.size() - maxTail, after.size()));
  }

  private List<Ambient> ambientFor(AgentId agentId) {
    return ambient.stream()
        .map(source -> source.forAgent(agentId))
        .flatMap(Optional::stream)
        .toList();
  }
}
