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
package org.jwcarman.nessy.agent.host;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.TurnOutcome;
import org.jwcarman.nessy.agent.support.ScriptedModel;
import org.jwcarman.nessy.agent.support.TestSettings;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.api.turn.Subscription;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.spi.model.ModelEvent;

/**
 * {@link Console#approver()} (spec §3): scripted IO — hand-rolled streams, no mocking — proves it
 * renders the flattened {@link org.jwcarman.nessy.spi.approval.ApprovalRequest} and answers by
 * {@code request.id()} through {@link org.jwcarman.nessy.agent.Harness#approvals()}, verified the
 * same way {@link org.jwcarman.nessy.agent.AgentAskTest.Parked} verifies it: the gated tool
 * actually runs (or never does).
 */
class ConsoleApproverTest {

  record NoInput() {}

  /** A tool gated behind approval — records every actual execution. */
  private static final class GatedTool implements Tool<NoInput> {

    private final AtomicInteger invocations = new AtomicInteger();

    @Override
    public String name() {
      return "restart";
    }

    @Override
    public String description() {
      return "gated behind approval";
    }

    @Override
    public Class<NoInput> inputType() {
      return NoInput.class;
    }

    @Override
    public Awaited<ToolResult> execute(NoInput input, ToolContext context) {
      invocations.incrementAndGet();
      return Awaited.ready(ToolResult.ok("restarted"));
    }
  }

  private static Console buildParkedConsole(
      GatedTool tool, byte[] answer, ByteArrayOutputStream captured) {
    var call = new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());
    var model =
        new ScriptedModel(
            List.of(
                List.of(new ModelEvent.ToolUseEmitted(call, null)),
                List.of(new ModelEvent.TextChunk("done"))));
    return Nessy.cli()
        .model(model)
        .systemPrompt(TestSettings.SYSTEM_PROMPT)
        .settings(TestSettings.settings())
        .grants(ToolGrant.grant(tool, UsagePolicy.requireApproval()))
        .in(new ByteArrayInputStream(answer))
        .out(new PrintStream(captured, true, StandardCharsets.UTF_8))
        .build();
  }

  @Nested
  class Approving {

    @Test
    void a_y_answer_renders_the_flattened_request_and_approves_by_id() throws Exception {
      var tool = new GatedTool();
      var captured = new ByteArrayOutputStream();
      try (var console =
          buildParkedConsole(tool, "y\n".getBytes(StandardCharsets.UTF_8), captured)) {
        TurnOutcome outcome = console.agent().ask("please restart");
        assertThat(outcome).isInstanceOf(TurnOutcome.Parked.class);
        var request = ((TurnOutcome.Parked) outcome).ask();

        var settled = new CompletableFuture<Void>();
        try (Subscription subscription =
            console
                .agent()
                .subscribe(
                    event -> {
                      if (event instanceof TurnEvent.TurnEnded) {
                        settled.complete(null);
                      }
                    })) {
          console.approver().decide(request);
          settled.get(5, TimeUnit.SECONDS);
        }

        assertThat(tool.invocations).hasValue(1);
        String rendered = captured.toString(StandardCharsets.UTF_8);
        assertThat(rendered).contains("restart").contains("cli").contains("approved");
      }
    }
  }

  @Nested
  class Denying {

    @Test
    void an_n_answer_reads_a_reason_and_denies_by_id_without_running_the_tool() throws Exception {
      var tool = new GatedTool();
      var captured = new ByteArrayOutputStream();
      try (var console =
          buildParkedConsole(tool, "n\nnot today\n".getBytes(StandardCharsets.UTF_8), captured)) {
        TurnOutcome outcome = console.agent().ask("please restart");
        assertThat(outcome).isInstanceOf(TurnOutcome.Parked.class);
        var request = ((TurnOutcome.Parked) outcome).ask();

        var settled = new CompletableFuture<Void>();
        try (Subscription subscription =
            console
                .agent()
                .subscribe(
                    event -> {
                      if (event instanceof TurnEvent.TurnEnded) {
                        settled.complete(null);
                      }
                    })) {
          console.approver().decide(request);
          settled.get(5, TimeUnit.SECONDS);
        }

        assertThat(tool.invocations).hasValue(0);
        assertThat(captured.toString(StandardCharsets.UTF_8)).contains("denied: not today");
      }
    }
  }
}
