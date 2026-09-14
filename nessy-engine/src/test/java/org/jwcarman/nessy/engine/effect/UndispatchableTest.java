package org.jwcarman.nessy.engine.effect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.RetryPolicy;
import org.jwcarman.nessy.engine.EngineUnderTest;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spi.inference.InferenceRequest;
import org.jwcarman.nessy.spi.inference.InferenceResult;
import org.jwcarman.nessy.spi.narration.AgentNarrator;

/**
 * An effect nobody can read still ends its agent's turn.
 *
 * <p>The realistic cause is not a corrupted byte but a rollback: a newer build writes an effect
 * type, is rolled back, and the running code meets a payload naming something it cannot construct.
 * The agent is waiting on that call and nothing else will ever wake it, so the row has to be able
 * to say what to tell it without anyone understanding what the effect was.
 */
class UndispatchableTest {

  private EngineUnderTest engine;

  /**
   * One engine per test, and each built around the model that test needs.
   *
   * <p>The provider is a factory-level setting -- one model serves every harness an engine hands
   * out -- so a class that varies what the model asks for varies the engine, not the harness.
   */
  private void running(InferenceProvider model) {
    engine = new EngineUnderTest(model);
  }

  @AfterEach
  void stopEngine() {
    if (engine != null) {
      engine.close();
    }
  }

  private static final AgentType TYPE = new AgentType("undispatchable");

  @Test
  void anEffectThatCannotBeDecodedDeliversItsStoredFailureResponse() {
    AgentId agentId = new AgentId(UUID.randomUUID());
    running(new NeverCalled());
    Harness<String> harness =
        engine
            .harnesses()
            .create(
                String.class,
                config ->
                    config
                        .agentType(TYPE)
                        .systemPrompt("You are a test assistant.")
                        .inference(in -> in.model("a-model").retryPolicy(new RetryPolicy.Never()))
                        .effects(
                            e ->
                                e.maxInFlight(2)
                                    // Long enough that the payload is corrupted before any pass can
                                    // look at it.
                                    .pollInterval(Duration.ofSeconds(3))));

    harness.observe(agentId, "this will not be dispatchable");

    // Stand in for the rollback: the row now names something no build can construct.
    int corrupted =
        engine
            .jdbc()
            .sql("UPDATE nessy_agent_effect SET payload = ? WHERE agent_id = ?")
            .params(
                "{\"type\":\"AnEffectFromTheFuture\"}".getBytes(StandardCharsets.UTF_8),
                agentId.value())
            .update();
    assertThat(corrupted).as("the effect row must exist to be corrupted").isEqualTo(1);

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              assertThat(stateOf(agentId))
                  .as("the stored response ended the turn, so the agent is not stuck")
                  .isEqualTo("Idle");
              assertThat(effectsFor(agentId))
                  .as("an effect nothing can ever perform is retired, not retried forever")
                  .isZero();
            });
  }

  private String stateOf(AgentId agentId) {
    return engine
        .jdbc()
        .sql("SELECT state_type FROM nessy_agent_state WHERE agent_id = ?")
        .params(agentId.value())
        .query(String.class)
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

  /** If this is ever called the payload was decoded, which is the opposite of the point. */
  static class NeverCalled implements InferenceProvider {

    @Override
    public InferenceResult infer(InferenceRequest request, AgentNarrator narrator) {
      throw new IllegalStateException("the undecodable effect was somehow dispatched");
    }
  }
}
