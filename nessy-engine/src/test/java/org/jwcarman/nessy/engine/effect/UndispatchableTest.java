package org.jwcarman.nessy.engine.effect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;
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
 * An effect nobody can read still ends its agent's turn.
 *
 * <p>The realistic cause is not a corrupted byte but a rollback: a newer build writes an effect
 * type, is rolled back, and the running code meets a payload naming something it cannot construct.
 * The agent is waiting on that call and nothing else will ever wake it, so the row has to be able
 * to say what to tell it without anyone understanding what the effect was.
 */
class UndispatchableTest {

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
                        .effects(e -> e.maxInFlight(2)));

    // Stand in for the rollback: the row this agent's effect is written as names something no
    // build can construct. Rewritten as it is inserted, because the effect is taken the moment
    // observe commits -- there is no later moment to corrupt it in before a pass looks.
    rewriteEffectsOf(agentId, "{\"type\":\"AnEffectFromTheFuture\"}");
    try {
      harness.observe(agentId, "this will not be dispatchable");
    } finally {
      stopRewriting(agentId);
    }

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

  private static String triggerOf(AgentId agentId) {
    return "undispatchable_" + agentId.value().toString().replace("-", "");
  }

  private void rewriteEffectsOf(AgentId agentId, String payload) {
    String name = triggerOf(agentId);
    engine
        .jdbc()
        .sql(
            ("CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                    + " IF NEW.agent_id = '%s' THEN NEW.payload := convert_to('%s', 'UTF8');"
                    + " END IF; RETURN NEW; END $$")
                .formatted(name, agentId.value(), payload))
        .update();
    engine
        .jdbc()
        .sql(
            "CREATE TRIGGER %s BEFORE INSERT ON nessy_agent_effect FOR EACH ROW EXECUTE FUNCTION %s()"
                .formatted(name, name))
        .update();
  }

  private void stopRewriting(AgentId agentId) {
    String name = triggerOf(agentId);
    engine.jdbc().sql("DROP TRIGGER %s ON nessy_agent_effect".formatted(name)).update();
    engine.jdbc().sql("DROP FUNCTION %s()".formatted(name)).update();
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
