package org.jwcarman.nessy.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.Entity;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityTypeKey;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.memory.Memory;
import org.jwcarman.nessy.api.message.AnswerMessage;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.HistoryMessage;
import org.jwcarman.nessy.api.model.ModelResult;
import org.jwcarman.nessy.api.model.StopReason;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

/**
 * Being forgotten while the last turn is still writing itself down.
 *
 * <p>An instruction batch is one task on the blocking executor, and nothing orders two of them. The
 * engine's own state does not help: {@code finished()} is applied when a turn's decision is
 * RETURNED, so an agent calls itself idle while its answer is still on its way to the transcript. A
 * forget arriving in that window took the idle path and its delete raced the write it should have
 * followed.
 *
 * <p>Against a real executor that is about one run in five. Here the test owns the executor, so it
 * is every run.
 */
@DisplayName("A forget arriving while a turn is still writing")
class ForgetRaceTest {

  private static ActorTestKit testKit;

  private HandBrake brake;

  /** Opened once the finishing turn has written its answer, letting the forget carry on. */
  private final CountDownLatch answerWritten = new CountDownLatch(1);

  @BeforeAll
  static void start() {
    testKit = ClusterOfOne.start();
  }

  @AfterAll
  static void stop() {
    testKit.shutdownTestKit();
  }

  @AfterEach
  void release() {
    brake.shutdown();
  }

  @Test
  @DisplayName("does not leave the answer behind it")
  void the_write_does_not_land_inside_the_delete() throws InterruptedException {
    brake = new HandBrake();
    TestProbe<ClusterSharding.ShardCommand> shard = testKit.createTestProbe();
    Engines.Parts parts =
        Engines.of(
            testKit.system(),
            AgentType.of("racing"),
            answeringThenHolding(),
            List.of(),
            brake,
            this::pausingAfterForget);
    AgentId agentId = shardedAgent("racing", parts, shard);

    parts.backlog().offer(agentId, new HouseEvents.HouseEvent("kitchen", "door opened"));
    tell(agentId, new NessyMessage.BacklogUpdated(Map.of()));

    // The turn has decided how it ends; its answer is not written yet -- that batch is held.
    await().atMost(Duration.ofSeconds(10)).until(() -> brake.pending() >= 1);

    tell(agentId, new NessyMessage.Forget(Map.of()));
    await().atMost(Duration.ofSeconds(10)).until(() -> brake.pending() >= 2);

    brake.releaseTogether();

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(parts.remembered().of(agentId))
                    .as("the turn got its answer written down")
                    .isNotEmpty());
    answerWritten.countDown();

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(parts.remembered().of(agentId))
                    .as("what a forgotten agent remembers")
                    .isEmpty());
  }

  /**
   * A memory that stops the world once it has been wiped.
   *
   * <p>Forgetting wipes memory, then the backlog, then claims. Holding it open right after the wipe
   * is the window the finishing turn's write lands in -- it redeems its answer from claims, which
   * are still there, and puts it back into a memory that was just emptied.
   */
  private Memory pausingAfterForget(Memory delegate) {
    return new Memory() {
      @Override
      public Context recall(AgentId agentId) {
        return delegate.recall(agentId);
      }

      @Override
      public void remember(AgentId agentId, HistoryMessage message) {
        delegate.remember(agentId, message);
      }

      @Override
      public void forget(AgentId agentId) {
        delegate.forget(agentId);
        try {
          answerWritten.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    };
  }

  /** Scripted, and it pulls the hand brake on its way out: everything after this is the test's. */
  private Model answeringThenHolding() {
    Model scripted =
        Engines.saying(
            List.of(
                new ModelResult.Answered(
                    new AnswerMessage(List.of(new TextBlock("done"))),
                    StopReason.END_TURN,
                    Usage.unreported())));
    return new Model() {
      @Override
      public org.jwcarman.nessy.api.model.ModelId id() {
        return scripted.id();
      }

      @Override
      public ModelStream stream(ModelRequest request) {
        ModelStream answering = scripted.stream(request);
        brake.pull();
        return answering;
      }
    };
  }

  private static AgentId shardedAgent(
      String name, Engines.Parts parts, TestProbe<ClusterSharding.ShardCommand> shard) {
    AgentType type = AgentType.of(name);
    EntityTypeKey<NessyMessage> key = EntityTypeKey.create(NessyMessage.class, type.name());
    ClusterSharding.get(testKit.system())
        .init(
            Entity.of(
                    key,
                    context ->
                        AgentActor.create(
                            new AgentActor.Dependencies(type, parts.instructions(), Traces.noop()),
                            AgentId.of(context.getEntityId()),
                            shard.ref()))
                .withStopMessage(new NessyMessage.Stop(Map.of())));
    return AgentId.of(name + "-1");
  }

  private static void tell(AgentId agentId, NessyMessage message) {
    ClusterSharding.get(testKit.system())
        .entityRefFor(EntityTypeKey.create(NessyMessage.class, "racing"), agentId.value())
        .tell(message);
  }
}
