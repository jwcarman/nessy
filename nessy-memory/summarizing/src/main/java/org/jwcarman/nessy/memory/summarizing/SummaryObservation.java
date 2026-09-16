package org.jwcarman.nessy.memory.summarizing;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import java.util.function.Supplier;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;

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
  static final String KIND = "nessy.summary.kind";
  static final String OUTCOME = "nessy.summary.outcome";

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
            .lowCardinalityKeyValue("nessy.agent.type", agentType.value())
            .lowCardinalityKeyValue(KIND, kind)
            .lowCardinalityKeyValue(OUTCOME, "none")
            .highCardinalityKeyValue("gen_ai.agent.id", agentId.value().toString())
            .start();
    try (Observation.Scope _ = observation.openScope()) {
      observation.lowCardinalityKeyValue(OUTCOME, work.get());
    } catch (RuntimeException e) {
      observation.error(e);
      throw e;
    } finally {
      observation.stop();
    }
  }
}
