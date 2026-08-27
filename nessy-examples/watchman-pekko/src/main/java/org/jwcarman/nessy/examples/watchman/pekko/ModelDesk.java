/*
 * Copyright © 2026 James Carman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jwcarman.nessy.examples.watchman.pekko;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.delivery.ConsumerController;
import org.apache.pekko.actor.typed.delivery.WorkPullingProducerController;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.receptionist.ServiceKey;

/**
 * The model desk: ONE per node, owning every scrap of the work-pulling protocol.
 *
 * <p>An agent that wants the model does {@code desk.tell(new CallModel(...))} — a plain
 * fire-and-forget. It never sees a {@code Start}, a {@code RequestNext} or a demand token. That
 * quarantine is the point: a work-pulling producer must send exactly one message per demand signal,
 * and an agent with no demand in hand would have to stash, inside a mailbox that is also carrying
 * approvals and tool results.
 *
 * <p>The cost the desk absorbs is the queue below. Work pulling backpressures the PRODUCER, and our
 * producers are agents that cannot block, so the queue has to live somewhere. For one watchman it
 * never exceeds one entry; a real multi-agent deployment needs a bound and a rejection policy here.
 */
public final class ModelDesk {

  /** Workers register under this key by existing. Capacity is a set of actors, not a number. */
  public static final ServiceKey<ConsumerController.Command<ModelJob>> MODEL_WORKERS =
      ServiceKey.create(ConsumerController.serviceKeyClass(), "watchman-model-worker");

  public record ModelJob(
      List<Turn> transcript, ActorRef<AgentActor.Command> replyTo, Map<String, String> trace) {}

  public sealed interface Command {}

  /** What an agent sends. */
  public record CallModel(
      List<Turn> transcript, ActorRef<AgentActor.Command> replyTo, Map<String, String> trace)
      implements Command {}

  /** The controller telling us a worker is free. */
  public record Demand(WorkPullingProducerController.RequestNext<ModelJob> requestNext)
      implements Command {}

  private ModelDesk() {}

  public static Behavior<Command> create() {
    return Behaviors.setup(
        context -> {
          ActorRef<WorkPullingProducerController.Command<ModelJob>> producer =
              context.spawn(
                  WorkPullingProducerController.create(
                      ModelJob.class, "watchman-model-desk", MODEL_WORKERS, Optional.empty()),
                  "producer");
          producer.tell(
              new WorkPullingProducerController.Start<>(
                  context.messageAdapter(
                      WorkPullingProducerController.requestNextClass(), Demand::new)));
          return waiting(new ArrayDeque<>(), Optional.empty());
        });
  }

  private static Behavior<Command> waiting(
      Deque<CallModel> pending,
      Optional<WorkPullingProducerController.RequestNext<ModelJob>> demand) {
    return Behaviors.receive(Command.class)
        .onMessage(
            CallModel.class,
            call -> {
              pending.addLast(call);
              return dispatch(pending, demand);
            })
        .onMessage(Demand.class, next -> dispatch(pending, Optional.of(next.requestNext())))
        .build();
  }

  private static Behavior<Command> dispatch(
      Deque<CallModel> pending,
      Optional<WorkPullingProducerController.RequestNext<ModelJob>> demand) {
    if (pending.isEmpty() || demand.isEmpty()) {
      return waiting(pending, demand);
    }
    CallModel call = pending.removeFirst();
    demand.get().sendNextTo().tell(new ModelJob(call.transcript(), call.replyTo(), call.trace()));
    return waiting(pending, Optional.empty());
  }
}
