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

import java.util.concurrent.Executor;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.delivery.ConsumerController;
import org.apache.pekko.actor.typed.javadsl.Behaviors;

/**
 * One model-calling worker: a work-pulling CONSUMER.
 *
 * <p>The line that makes this a real limiter is the ORDER in {@code Replied}: the confirmation goes
 * out only after the model has answered, and a ConsumerController does not request its next job
 * until the current one is confirmed. So each worker holds exactly one in-flight model call, and
 * the number of workers IS the number of concurrent calls — no semaphore, no counter, no config.
 */
public final class ModelWorker {

  public sealed interface Command {}

  public record Delivered(ConsumerController.Delivery<ModelDesk.ModelJob> delivery)
      implements Command {}

  public record Replied(
      ModelReply reply,
      ActorRef<AgentActor.Command> agent,
      java.util.Map<String, String> trace,
      ActorRef<ConsumerController.Confirmed> confirmTo)
      implements Command {}

  private ModelWorker() {}

  public static Behavior<Command> create(WatchmanModel model, Executor blocking, Traces traces) {
    return Behaviors.setup(
        context -> {
          ActorRef<ConsumerController.Command<ModelDesk.ModelJob>> consumer =
              context.spawn(ConsumerController.create(ModelDesk.MODEL_WORKERS), "consumer");
          consumer.tell(
              new ConsumerController.Start<>(
                  context.messageAdapter(ConsumerController.deliveryClass(), Delivered::new)));

          return Behaviors.receive(Command.class)
              .onMessage(
                  Delivered.class,
                  delivered -> {
                    ModelDesk.ModelJob job = delivered.delivery().message();
                    context.pipeToSelf(
                        traces.inSpan(
                            "model call",
                            job.trace(),
                            () -> model.reply(job.transcript(), blocking)),
                        (reply, failure) ->
                            new Replied(
                                failure == null
                                    ? reply
                                    : new ModelReply.Failed(String.valueOf(failure)),
                                job.replyTo(),
                                job.trace(),
                                delivered.delivery().confirmTo()));
                    return Behaviors.same();
                  })
              .onMessage(
                  Replied.class,
                  replied -> {
                    replied
                        .agent()
                        .tell(new AgentActor.ModelReplied(replied.reply(), replied.trace()));
                    replied.confirmTo().tell(ConsumerController.confirmed());
                    return Behaviors.same();
                  })
              .build();
        });
  }
}
