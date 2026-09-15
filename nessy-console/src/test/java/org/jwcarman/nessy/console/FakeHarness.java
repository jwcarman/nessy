package org.jwcarman.nessy.console;

import java.util.ArrayList;
import java.util.List;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentEventListener;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Harness;

/**
 * A harness that answers each observation with a scripted run of events.
 *
 * <p>Narrated on THIS thread, which the real engine would not do -- but the loop must not care
 * which thread an event arrives on, and a test that had to start one would be racing.
 */
final class FakeHarness implements Harness<String> {

  private static final AgentType TYPE = new AgentType("chat");

  private final List<List<AgentEvent>> answers;
  private final List<String> observed = new ArrayList<>();
  private AgentEventListener narrator = AgentEventListener.none();
  private int next;

  @SafeVarargs
  FakeHarness(List<AgentEvent>... answers) {
    this.answers = List.of(answers);
  }

  /** The engine is told its narrator at construction; a fake is told afterwards. */
  void narrateTo(AgentEventListener narrator) {
    this.narrator = narrator;
  }

  @Override
  public void observe(AgentId agentId, String observation) {
    observed.add(observation);
    if (next >= answers.size()) {
      return;
    }
    answers.get(next++).forEach(event -> narrator.on(TYPE, agentId, event));
  }

  @Override
  public void terminate(AgentId agentId) {
    narrator.on(TYPE, agentId, new AgentEvent.Terminated());
  }

  List<String> observed() {
    return List.copyOf(observed);
  }
}
