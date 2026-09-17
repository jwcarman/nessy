package org.jwcarman.nessy.engine.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.engine.store.TurnHistories;
import org.jwcarman.nessy.engine.store.TurnHistory;

/**
 * The story, read in a {@code nessy.context history} span: how long it took to fetch the verbatim
 * tail, and how many turns came back.
 *
 * <p>As a conversation grows this is the read that grows with it, and inside the effect it is
 * indistinguishable from time the model took.
 */
public final class ObservedTurnHistories {

  private ObservedTurnHistories() {}

  public static TurnHistories wrap(TurnHistories delegate, ObservationRegistry observations) {
    Objects.requireNonNull(delegate, "delegate must not be null");
    Objects.requireNonNull(observations, "observations must not be null");
    return (agentType, agentId) ->
        history(delegate.forAgent(agentType, agentId), observations, agentType, agentId);
  }

  private static TurnHistory history(
      TurnHistory delegate,
      ObservationRegistry observations,
      AgentType agentType,
      AgentId agentId) {
    return new TurnHistory() {
      @Override
      public List<Turn> lastTurns(int turns) {
        return read(() -> delegate.lastTurns(turns));
      }

      @Override
      public List<Turn> turnsFrom(long fromTurn) {
        return read(() -> delegate.turnsFrom(fromTurn));
      }

      @Override
      public List<Turn> lastTurnsAfter(TurnId through, int turns) {
        return read(() -> delegate.lastTurnsAfter(through, turns));
      }

      @Override
      public long turnsAfter(long through) {
        // Counting is not reading: nothing of the story is fetched, so there is nothing to time.
        return delegate.turnsAfter(through);
      }

      private List<Turn> read(Supplier<List<Turn>> reading) {
        return observe(
            observations,
            "nessy.context.history",
            "nessy.context history",
            new Identity(agentType, agentId),
            observation -> {
              List<Turn> turns = reading.get();
              observation.highCardinalityKeyValue(
                  "nessy.context.turns", String.valueOf(turns.size()));
              return turns;
            });
      }
    };
  }

  /**
   * Opens one span and runs the work in it. No guard: {@code createNotStarted} answers a no-op
   * registry with a no-op observation, which takes every tag and records nothing -- and a guard at
   * wiring time would be wrong anyway, since a registry with no handlers YET looks no-op.
   */
  private static <T> T observe(
      ObservationRegistry observations,
      String name,
      String spanName,
      Identity whose,
      Function<Observation, T> work) {
    Observation observation =
        Observation.createNotStarted(name, observations).contextualName(spanName);
    if (whose != null) {
      whose.on(observation, null);
    }
    return observation.observe(() -> work.apply(observation));
  }

  private static Identity whose(ObservationRegistry observations) {
    return Identity.current(observations);
  }
}
