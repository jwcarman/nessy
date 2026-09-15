package org.jwcarman.nessy.engine.effect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.RetryPolicy;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spi.inference.InferenceRequest;
import org.jwcarman.nessy.spi.inference.InferenceResult;
import org.jwcarman.nessy.spi.narration.AgentNarrator;

/**
 * Giving up and failing to say so are not the same thing.
 *
 * <p>When the fold itself is what is broken, the outcome that ends the turn cannot be delivered
 * either -- so there is no way to both stop trying and leave the agent in a sane state. Between
 * retrying forever and stranding the agent forever, the obligation is kept: the row stays, its
 * watchdog brings it back, and the failure is logged every time round rather than escaping
 * silently. A loop somebody can see beats a hang nobody can.
 */
class GiveUpUndeliverableTest {

  private EngineFixture engine;

  /**
   * One engine per test, and each built around the model that test needs.
   *
   * <p>The provider is a factory-level setting -- one model serves every harness an engine hands
   * out -- so a class that varies what the model asks for varies the engine, not the harness.
   */
  private void running(InferenceProvider model) {
    engine = new EngineFixture(model);
  }

  @AfterEach
  void stopEngine() {
    if (engine != null) {
      engine.close();
    }
  }

  private static final AtomicInteger DELIVERIES = new AtomicInteger();

  @Test
  void anUndeliverableGiveUpKeepsTheRowRatherThanStrandingTheAgent() {
    AgentType type = new AgentType("undeliverable");
    AgentId agentId = new AgentId(UUID.randomUUID());
    DELIVERIES.set(0);

    running(new AlwaysBroken());
    Harness<String> harness =
        engine
            .harnesses()
            .create(
                String.class,
                config ->
                    config
                        .agentType(type)
                        .systemPrompt("You are a test assistant.")
                        .inference(
                            in ->
                                in.model("a-model")
                                    .retryPolicy(new RetryPolicy.Never())
                                    // Short, so the watchdog brings the row back inside the test
                                    // rather than
                                    // in five minutes -- the recovery asserted is the timeout, not
                                    // the poller.
                                    .timeout(Duration.ofSeconds(1)))
                        .effects(
                            e ->
                                e.maxInFlight(2)
                                    // Long enough that the state is corrupted before any pass
                                    // looks. At a short
                                    // interval the effect is taken, failed, given up on and retired
                                    // while this test
                                    // is still setting up, and there is nothing left to observe.
                                    .pollInterval(Duration.ofSeconds(3))));

    harness.observe(agentId, "the fold will break before this is answered");

    // Break every fold from here on. The model call fails, the policy says give up, and the
    // delivery that would end the turn fails for the same reason.
    breakTheFold(agentId);

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(attemptsOn(agentId))
                    .as("the row is kept and tried again, not retired with the turn unended")
                    .isGreaterThan(1));

    assertThat(effectsFor(agentId))
        .as("retiring it would end the attempts and leave the agent waiting forever")
        .isEqualTo(1);
  }

  /**
   * Corrupts the stored state so that decoding it inside the fold throws, which is what makes both
   * the ordinary delivery and the give-up delivery fail.
   */
  private void breakTheFold(AgentId agentId) {
    assertThat(effectsFor(agentId))
        .as("observe commits the effect with the state, so it is already there")
        .isEqualTo(1);
    engine
        .jdbc()
        .sql("UPDATE nessy_agent_state SET payload = ? WHERE agent_id = ?")
        .params("{\"type\":\"AStateFromTheFuture\"}".getBytes(), agentId.value())
        .update();
  }

  private int attemptsOn(AgentId agentId) {
    return engine
        .jdbc()
        .sql(
            "SELECT coalesce(max(attempts_made),0) FROM nessy_agent_effect" + " WHERE agent_id = ?")
        .params(agentId.value())
        .query(Integer.class)
        .single();
  }

  private int effectsFor(AgentId agentId) {
    return engine
        .jdbc()
        .sql("SELECT count(*) FROM nessy_agent_effect WHERE agent_id = ?")
        .params(agentId.value())
        .query(Integer.class)
        .single();
  }

  static class AlwaysBroken implements InferenceProvider {

    @Override
    public InferenceResult infer(InferenceRequest request, AgentNarrator narrator) {
      DELIVERIES.incrementAndGet();
      throw new IllegalStateException("nope");
    }
  }
}
