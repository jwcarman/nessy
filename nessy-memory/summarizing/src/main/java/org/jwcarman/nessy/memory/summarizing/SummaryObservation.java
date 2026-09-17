package org.jwcarman.nessy.memory.summarizing;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import java.util.function.Supplier;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.engine.observability.Identity;

/**
 * The span a background summary runs in: {@code nessy.summary}, one per attempt that found work to
 * do, tagged with the agent type, which summariser ({@code head} or {@code episode}) and how it
 * came out.
 *
 * <p>A summary is triggered by a turn ending and runs later on a thread of its own, but the engine
 * carries the observation current at the turn's end onto that thread, so this span is a child of
 * the turn's last effect, however long after it ran. The model call inside it is a {@code chat}
 * span like any other, nested here. Outcomes: {@code written}, {@code nothing} (another process got
 * there first, or the story had moved on), {@code lease-refused}, {@code fault} (the model would
 * not answer) and {@code empty} (it answered with nothing).
 */
public final class SummaryObservation {

  public static final String NAME = "nessy.summary";
  static final String KIND_TAG = "nessy.summary.kind";
  static final String OUTCOME_TAG = "nessy.summary.outcome";

  /**
   * What an attempt came to, as {@code nessy.summary.outcome} reports it. Here rather than in each
   * summariser, because two of them say the same five words and a dashboard groups by them.
   */
  public static final String WRITTEN = "written";

  /** Nothing to do, or another process got there first. */
  public static final String NOTHING = "nothing";

  /** Somebody else holds the lease; this attempt simply moves on. */
  public static final String LEASE_REFUSED = "lease-refused";

  /** The model would not answer. */
  public static final String FAULT = "fault";

  /** The model answered with nothing, so there was nothing to write. */
  public static final String EMPTY = "empty";

  private final ObservationRegistry registry;
  private final String kind;
  private final AgentType agentType;

  public SummaryObservation(ObservationRegistry registry, String kind, AgentType agentType) {
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
    this.kind = Objects.requireNonNull(kind, "kind must not be null");
    this.agentType = Objects.requireNonNull(agentType, "agentType must not be null");
  }

  /**
   * Runs {@code work} in the span; the work says how it came out through the handle it is given.
   */
  public void observe(AgentId agentId, Supplier<String> work) {
    if (registry.isNoop()) {
      work.get();
      return;
    }
    Observation observation =
        Observation.createNotStarted(NAME, registry)
            .contextualName(NAME + " " + kind)
            .lowCardinalityKeyValue(KIND_TAG, kind)
            .lowCardinalityKeyValue(OUTCOME_TAG, "none");
    new Identity(agentType, agentId).on(observation, null);
    observation.start();
    try (var _ = observation.openScope()) {
      observation.lowCardinalityKeyValue(OUTCOME_TAG, work.get());
    } catch (RuntimeException e) {
      observation.error(e);
      throw e;
    } finally {
      observation.stop();
    }
  }
}
