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

import java.util.concurrent.Executor;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.delivery.ConsumerController;
import org.apache.pekko.actor.typed.javadsl.Behaviors;

/**
 * THROWAWAY SPIKE. One model-calling worker: a work-pulling CONSUMER.
 *
 * <p>Registers itself with the Receptionist under {@link SpikeModelDesk#MODEL_WORKERS} simply by
 * existing — {@link ConsumerController} does the registration. Start ten of these and the desk
 * finds ten; kill three and the desk keeps going with seven, with no configuration anywhere. That
 * is the "standardised paths" property: capacity is a set of registered actors, not a number in a
 * config file.
 *
 * <p><b>The line that makes this a real limiter</b> is the ordering in {@code onMessage(Replied)}:
 * {@code confirmTo.tell(confirmed())} happens AFTER the model has answered. A ConsumerController
 * does not request its next job until the current one is confirmed, so this worker holds exactly
 * one in-flight model call, always. Concurrency is bounded by how many workers exist, structurally,
 * with no semaphore and no counter.
 */
public final class SpikeModelWorker {

  public sealed interface Command {}

  /** A job pulled from the desk. */
  public record Delivered(ConsumerController.Delivery<SpikeModelDesk.ModelJob> delivery)
      implements Command {}

  /** Our own model call came back. */
  public record Replied(
      SpikeModelReply reply,
      ActorRef<AgentActor.Command> agent,
      ActorRef<ConsumerController.Confirmed> confirmTo)
      implements Command {}

  private SpikeModelWorker() {}

  public static Behavior<Command> create(SpikeModel model, Executor blocking) {
    return Behaviors.setup(
        context -> {
          ActorRef<ConsumerController.Command<SpikeModelDesk.ModelJob>> consumer =
              context.spawn(ConsumerController.create(SpikeModelDesk.MODEL_WORKERS), "consumer");
          consumer.tell(
              new ConsumerController.Start<>(
                  context.messageAdapter(ConsumerController.deliveryClass(), Delivered::new)));

          return Behaviors.receive(Command.class)
              .onMessage(
                  Delivered.class,
                  delivered -> {
                    SpikeModelDesk.ModelJob job = delivered.delivery().message();
                    context.pipeToSelf(
                        model.reply(job.transcript(), blocking),
                        (reply, failure) ->
                            new Replied(
                                failure == null
                                    ? reply
                                    : new SpikeModelReply.Said("the model failed: " + failure),
                                job.replyTo(),
                                delivered.delivery().confirmTo()));
                    return Behaviors.same();
                  })
              .onMessage(
                  Replied.class,
                  replied -> {
                    replied.agent().tell(new AgentActor.ModelReplied(replied.reply()));
                    // Only now do we become available for the next job.
                    replied.confirmTo().tell(ConsumerController.confirmed());
                    return Behaviors.same();
                  })
              .build();
        });
  }
}
