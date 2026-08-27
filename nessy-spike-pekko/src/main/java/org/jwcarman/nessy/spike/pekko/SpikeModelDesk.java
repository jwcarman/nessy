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
package org.jwcarman.nessy.spike.pekko;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.delivery.ConsumerController;
import org.apache.pekko.actor.typed.delivery.WorkPullingProducerController;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.receptionist.ServiceKey;

/**
 * THROWAWAY SPIKE. The model desk: ONE per node, owning the work-pulling producer controller.
 *
 * <p><b>Answering "where does the producer controller live?" from the code.</b> It lives here,
 * shared by every agent on the node, and the alternatives are not close:
 *
 * <ul>
 *   <li><b>One per agent</b> would give each agent its own view of the worker pool and its own
 *       demand, so ten idle agents would each hold demand tokens that a busy agent could not use.
 *       The pool would stop being a shared, bounded resource — which is the only reason to have it.
 *   <li><b>One per tool call / per model call</b> would mean creating a controller, waiting for it
 *       to discover workers via the Receptionist, and tearing it down, for every single call. The
 *       discovery round-trip alone costs more than the work.
 * </ul>
 *
 * A node-wide desk makes "at most N model calls in flight across the whole process" a true
 * statement, which is what a rate limit or a spend cap actually needs.
 *
 * <p><b>Answering "how intrusive is the protocol?" from the code.</b> Very. A work-pulling producer
 * may not simply {@code tell}: it must send {@code Start}, wait for a {@code RequestNext}, and send
 * exactly one message per demand signal. Putting that in {@link AgentActor} would mean every agent
 * carries a demand token and a pending-request buffer in its own state, and — much worse — an agent
 * that wants to call the model when it has no demand would have to stash, inside an actor whose
 * mailbox is also carrying approvals and tool results. This desk exists so that ceremony lives in
 * exactly one place: agents do {@code desk.tell(new CallModel(...))}, a plain fire-and-forget, and
 * know nothing about reliable delivery.
 *
 * <p>Note what the desk must do that a plain router would not: <b>buffer</b>. Work pulling gives
 * backpressure to the PRODUCER, but our producers are agents that cannot block, so the queue has to
 * live somewhere and it lives in {@link #pending}. That is the honest cost of demand-driven flow
 * control here — it converts "flood the workers" into "queue at the desk", which is better, but it
 * is not free and an unbounded deque is a memory leak waiting for a bad day.
 */
public final class SpikeModelDesk {

  /**
   * Workers register under this key by existing. A mistyped key would mean workers register under
   * one name while the controller waits on another — see the report on silent-discard risk.
   */
  public static final ServiceKey<ConsumerController.Command<ModelJob>> MODEL_WORKERS =
      ServiceKey.create(ConsumerController.serviceKeyClass(), "spike-model-worker");

  /** The unit of work that travels through the controller to a worker. */
  public record ModelJob(List<String> transcript, ActorRef<AgentActor.Command> replyTo) {}

  public sealed interface Command {}

  /** What an agent sends. A plain tell: no protocol, no demand, no ceremony. */
  public record CallModel(List<String> transcript, ActorRef<AgentActor.Command> replyTo)
      implements Command {}

  /** The controller telling us a worker is ready. Private to the desk. */
  public record Demand(WorkPullingProducerController.RequestNext<ModelJob> requestNext)
      implements Command {}

  private SpikeModelDesk() {}

  public static Behavior<Command> create() {
    return Behaviors.setup(
        context -> {
          ActorRef<WorkPullingProducerController.Command<ModelJob>> producer =
              context.spawn(
                  WorkPullingProducerController.create(
                      ModelJob.class, "spike-model-desk", MODEL_WORKERS, Optional.empty()),
                  "producer");
          producer.tell(
              new WorkPullingProducerController.Start<>(
                  context.messageAdapter(
                      WorkPullingProducerController.requestNextClass(), Demand::new)));
          return waiting(new ArrayDeque<>(), Optional.empty());
        });
  }

  /**
   * The desk's whole state: work nobody has asked for yet, and demand nobody has used yet. At most
   * one of the two is ever non-empty.
   */
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
    // One message per demand signal, exactly. The next RequestNext arrives when a worker is free.
    demand.get().sendNextTo().tell(new ModelJob(call.transcript(), call.replyTo()));
    return waiting(pending, Optional.empty());
  }
}
