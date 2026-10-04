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
package org.jwcarman.nessy.engine.story;

import static org.awaitility.Awaitility.await;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.DirectHarnessConfig;
import org.jwcarman.nessy.api.Narrated;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.NarrationListener;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.backend.DirectBackend;
import org.jwcarman.nessy.engine.harness.direct.DefaultDirectHarnessFactory;
import org.jwcarman.nessy.engine.schema.VictoolsJsonSchemaGenerator;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceResult;
import tools.jackson.databind.json.JsonMapper;

/** One scripted turn on the direct door, and the story events a listener heard while it ran. */
final class StoryTurn {

  private StoryTurn() {}

  /**
   * Runs one turn that answers "ok" and returns the story events the listener heard, oldest first.
   */
  static List<Narrated> heard(AgentType type, AgentId agent, DirectBackend backend, Clock clock) {
    return heard(
        type,
        agent,
        backend,
        clock,
        (request, narrator) ->
            new InferenceResult.Answer(List.of(new Block.Text("ok")), Usage.unreported()),
        config -> {});
  }

  /**
   * Runs one turn that answers "ok" for an application that labels its input, and returns the story
   * events the listener heard, oldest first.
   */
  static List<Narrated> heardWithAnInputLabel(
      AgentType type, AgentId agent, DirectBackend backend, Clock clock) {
    return heard(
        type,
        agent,
        backend,
        clock,
        (request, narrator) ->
            new InferenceResult.Answer(List.of(new Block.Text("ok")), Usage.unreported()),
        config -> config.inputLabel(said -> "Greeting"));
  }

  /** A model whose one reply is cut off at the output limit. */
  static InferenceProvider cutOffAtTheOutputLimit() {
    return (request, narrator) ->
        new InferenceResult.Truncated(List.of(new Block.Text("the lake is deep and")));
  }

  /**
   * Runs one turn whose reply the model cut off at the output limit, and returns the story events
   * the listener heard, oldest first.
   */
  static List<Narrated> heardWithATruncatedReply(
      AgentType type, AgentId agent, DirectBackend backend, Clock clock) {
    return heard(type, agent, backend, clock, cutOffAtTheOutputLimit(), config -> {});
  }

  /**
   * Runs one turn in which the model asks for a {@code lookup} call and then answers, and returns
   * the story events the listener heard, oldest first. Every model call reports its {@link Usage}.
   */
  static List<Narrated> heardWithAToolCall(
      AgentType type, AgentId agent, DirectBackend backend, Clock clock) {
    InferenceProvider provider =
        (request, narrator) ->
            request.context().turns().stream().anyMatch(turn -> !turn.exchanges().isEmpty())
                ? new InferenceResult.Answer(
                    List.of(new Block.Text("It is 1412 metres deep.")), Usage.of("a-model", 40, 9))
                : new InferenceResult.Actions(
                    List.of(
                        new Block.Commentary("Let me look that up."),
                        new Block.ToolCall("call_1", "lookup", "{\"q\":\"loch ness\"}")),
                    Usage.of("a-model", 25, 6));
    return heard(
        type,
        agent,
        backend,
        clock,
        provider,
        config ->
            config.tool(
                lookup(),
                t -> t.action(query -> "looked up " + query.q()).approver(decidesAsCarol())));
  }

  /**
   * Runs one turn in which the model asks for a {@code lookup} that never returns, so the call is
   * cut off at its deadline, and returns the story events the listener heard, oldest first.
   */
  static List<Narrated> heardWithAToolCallCutOffAtItsDeadline(
      AgentType type, AgentId agent, DirectBackend backend, Clock clock) {
    InferenceProvider provider =
        (request, narrator) ->
            request.context().turns().stream().anyMatch(turn -> !turn.exchanges().isEmpty())
                ? new InferenceResult.Answer(
                    List.of(new Block.Text("It did not answer.")), Usage.of("a-model", 40, 9))
                : new InferenceResult.Actions(
                    List.of(new Block.ToolCall("call_1", "lookup", "{\"q\":\"loch ness\"}")),
                    Usage.of("a-model", 25, 6));
    return heard(
        type,
        agent,
        backend,
        clock,
        provider,
        config ->
            config.tool(
                hanging(),
                t ->
                    t.timeout(Duration.ofMillis(50))
                        .action(query -> "looked up " + query.q())
                        .approver(decidesAsCarol())));
  }

  /** A lookup that blocks until it is interrupted. */
  private static Tool<Query> hanging() {
    return new Tool<>() {
      @Override
      public Class<Query> inputType() {
        return Query.class;
      }

      @Override
      public ToolName name() {
        return new ToolName("lookup");
      }

      @Override
      public String description() {
        return "never returns";
      }

      @Override
      public Awaited<ToolResult> call(ToolCallRequest<Query> request) {
        try {
          new CountDownLatch(1).await();
        } catch (InterruptedException _) {
          Thread.currentThread().interrupt();
        }
        throw new IllegalStateException("interrupted before returning");
      }
    };
  }

  /** The name every approval in these stories is decided under. */
  static final String DECIDER = "u_carol";

  /** An approver that allows every call and says who allowed it. */
  static Approver decidesAsCarol() {
    return _ -> Awaited.ready(ApprovalResult.approvedBy(DECIDER));
  }

  record Query(String q) {}

  /** A tool that answers every query the same way. */
  static Tool<Query> lookup() {
    return new Tool<>() {
      @Override
      public Class<Query> inputType() {
        return Query.class;
      }

      @Override
      public ToolName name() {
        return new ToolName("lookup");
      }

      @Override
      public String description() {
        return "looks a thing up";
      }

      @Override
      public Awaited<ToolResult> call(ToolCallRequest<Query> request) {
        return Awaited.ready(ToolResult.ok(new Block.Text("1412 metres")));
      }
    };
  }

  private static List<Narrated> heard(
      AgentType type,
      AgentId agent,
      DirectBackend backend,
      Clock clock,
      InferenceProvider provider,
      Consumer<DirectHarnessConfig<String>> tools) {
    List<Narrated> heard = new CopyOnWriteArrayList<>();
    NarrationListener recording = heard::add;
    try (DefaultDirectHarnessFactory factory =
        DefaultDirectHarnessFactory.of(
            f ->
                f.backend(backend)
                    .provider(ProviderId.of("test"), provider)
                    .schemas(new VictoolsJsonSchemaGenerator())
                    .mapper(JsonMapper.builder().build())
                    .clock(clock)
                    .listener(recording))) {
      DirectHarness<String, String> harness =
          factory.<String>create(
              type,
              c -> {
                tools.accept(c);
                c.systemPrompt("You are terse.")
                    .inputRenderer(said -> List.of(new Block.Text(said)))
                    .inference(in -> in.provider("test").model("a-model"));
              });
      harness.ask(agent, "hello");
    }
    await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () ->
                heard.stream()
                    .anyMatch(
                        narrated ->
                            narrated.event() instanceof Narration.Answered
                                && narrated.agentId().equals(agent)));
    return heard.stream().filter(narrated -> narrated.event() instanceof Narration.Story).toList();
  }
}
