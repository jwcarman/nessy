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
package org.jwcarman.nessy.engine;

import io.micrometer.tracing.Span;
import java.util.concurrent.Executor;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.delivery.ConsumerController;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.TokenEstimator;
import org.jwcarman.nessy.spi.Memory;
import org.jwcarman.nessy.spi.Remembrance;

/**
 * One model-calling worker: a work-pulling CONSUMER.
 *
 * <p>The line that makes this a real limiter is the ORDER in {@code Replied}: the confirmation goes
 * out only after the model has answered, and a ConsumerController does not request its next job
 * until the current one is confirmed. So each worker holds exactly one in-flight model call, and
 * the number of workers IS the number of concurrent calls — no semaphore, no counter, no config.
 */
public final class ModelWorker {

  /**
   * The same heuristic {@link Memories} budgets recall with — reused here only to make the size of
   * what came back VISIBLE, never to change what is sent. Recomputing it against the
   * already-budgeted {@link Context} reports what actually goes to the model, not the whole
   * journal.
   */
  private static final TokenEstimator TOKEN_ESTIMATOR = TokenEstimator.heuristic();

  public sealed interface Command {}

  public record Delivered(ConsumerController.Delivery<ModelDesk.ModelJob> delivery)
      implements Command {}

  public record Replied(
      ModelReply reply,
      ActorRef<AgentActor.NessyMessage> agent,
      java.util.Map<String, String> trace,
      ActorRef<ConsumerController.Confirmed> confirmTo)
      implements Command {}

  private ModelWorker() {}

  public static Behavior<Command> create(
      AgentModel model, Memories memories, Executor blocking, Traces traces) {
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
                    // Recall, call, remember -- ALL on the blocking executor. Recall is a
                    // journal read through Memory, and the assistant turn is remembered before the
                    // agent is told anything that depends on it. None of the three may touch a
                    // dispatcher.
                    context.pipeToSelf(
                        java.util.concurrent.CompletableFuture.supplyAsync(
                            () -> {
                              Memory memory = memories.forAgent(job.agentId());
                              // Three spans where there used to be one, all children of the same
                              // parent `chat` had before -- recall, the model call, and the
                              // remembrance are three different kinds of work with three different
                              // costs, and a trace that folds them into one leaf span cannot tell
                              // a slow database from a slow model.
                              Context recalled = recall(traces, job, memory);
                              ModelReply reply = converse(traces, job, model, recalled);
                              createMemory(traces, job, memory, reply);
                              return reply;
                            },
                            blocking),
                        (reply, failure) ->
                            new Replied(
                                failure == null
                                    ? reply
                                    : new ModelReply.Failed(
                                        org.jwcarman.nessy.api.message.Message.assistant(
                                            java.util.List.of(
                                                new org.jwcarman.nessy.api.message.TextBlock(
                                                    "the round failed: " + failure))),
                                        org.jwcarman.nessy.api.conversation.Usage.zero(),
                                        String.valueOf(failure)),
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

  /**
   * The journal read alone — {@code search_memory} in semconv's own {@code gen_ai.operation.name}
   * enum. Internal work, so no {@link Span.Kind}. Reported alongside is what was NOT visible
   * before: how much context came back, which is the number a token-budget bug would move.
   */
  private static Context recall(Traces traces, ModelDesk.ModelJob job, Memory memory) {
    return traces.inSpan(
        "search_memory",
        job.trace(),
        () -> {
          Context context = memory.recall();
          traces.tag("nessy.agent.id", job.agentId());
          traces.tag("gen_ai.operation.name", "search_memory");
          traces.tag("nessy.memory.messages", (long) context.messages().size());
          traces.tag("nessy.memory.tokens", context.tokens(TOKEN_ESTIMATOR));
          return context;
        });
  }

  /**
   * The model call ALONE — nothing else inside it. GenAI semconv names this span {@code chat}, and
   * it is CLIENT because the model is a remote service. A generic per-message interceptor would
   * have named it after {@code ModelJob}; only a declared span gets this right.
   */
  private static ModelReply converse(
      Traces traces, ModelDesk.ModelJob job, AgentModel model, Context recalled) {
    return traces.inSpan(
        "chat",
        Span.Kind.CLIENT,
        job.trace(),
        () -> {
          traces.tag("nessy.agent.id", job.agentId());
          traces.tag("gen_ai.operation.name", "chat");
          ModelReply result = model.reply(recalled);
          tagUsage(traces, result.usage());
          return result;
        });
  }

  /**
   * The assistant's turn is remembered before the agent hears about it — {@code create_memory} in
   * semconv's own enum, and internal work like {@code search_memory}.
   *
   * <p>A {@link Remembrance.AssistantMessage} naming tool_use ids is WITHHELD from every later
   * recall until each of those ids has a matching exchange — Nessy's fold does that, so a crash
   * between this write and the agent's persist leaves a turn that simply does not appear in the
   * context rather than one the model would reject. That reconciliation used to be ours.
   */
  private static void createMemory(
      Traces traces, ModelDesk.ModelJob job, Memory memory, ModelReply reply) {
    traces.inSpan(
        "create_memory",
        job.trace(),
        () -> {
          traces.tag("nessy.agent.id", job.agentId());
          traces.tag("gen_ai.operation.name", "create_memory");
          remember(memory, reply);
        });
  }

  private static void remember(Memory memory, ModelReply reply) {
    memory.remember(
        new Remembrance.AssistantMessage(
            org.jwcarman.nessy.api.Identifiers.next(), reply.message()));
  }

  /**
   * Names follow the OpenTelemetry GenAI semantic conventions, matching what {@link
   * ProviderAgentModel#record} already counts on the {@code MeterRegistry} so metrics and traces
   * agree.
   */
  private static void tagUsage(Traces traces, org.jwcarman.nessy.api.conversation.Usage usage) {
    traces.tag("gen_ai.usage.input_tokens", usage.inputTokens());
    traces.tag("gen_ai.usage.output_tokens", usage.outputTokens());
    traces.tag("gen_ai.usage.cache_read.input_tokens", usage.cacheReadInputTokens());
    traces.tag("gen_ai.usage.cache_write.input_tokens", usage.cacheWriteInputTokens());
  }
}
