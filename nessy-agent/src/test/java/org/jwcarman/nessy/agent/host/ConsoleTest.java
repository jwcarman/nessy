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
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.support.ScriptedModel;
import org.jwcarman.nessy.agent.support.TestSettings;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

/**
 * {@link Console#run()} (spec §3): the read-ask-print loop, all three {@link
 * org.jwcarman.nessy.agent.TurnOutcome} branches, plus the cli-path proof of Task 4's central fix —
 * an approval-requiring tool call on a {@link Nessy#cli()}-built agent used to hang {@code ask}
 * forever (the narration bypassed the per-id fanout entirely); it now parks cleanly, and every
 * narrated event reaches both a {@link org.jwcarman.nessy.agent.Agent#subscribe}d observer and the
 * harness's global {@code relay} exactly once.
 */
class ConsoleTest {

  @Nested
  class TheRunnerLoop {

    @Test
    void a_replied_turn_prints_the_assistants_text() {
      var model = new ScriptedModel(List.of(List.of(new ModelEvent.TextChunk("hello back"))));
      var captured = new ByteArrayOutputStream();
      try (var console =
          Nessy.cli()
              .model(model)
              .systemPrompt(TestSettings.SYSTEM_PROMPT)
              .settings(TestSettings.settings())
              .in(new ByteArrayInputStream("hello\n".getBytes(StandardCharsets.UTF_8)))
              .out(new PrintStream(captured, true, StandardCharsets.UTF_8))
              .build()) {
        console.run();
      }
      assertThat(captured.toString(StandardCharsets.UTF_8)).isEqualTo("hello back\n");
    }

    @Test
    void a_failed_turn_prints_the_reason_honestly() {
      Model exploding =
          new Model() {
            @Override
            public ModelStream stream(ModelRequest request) {
              throw new IllegalStateException("boom");
            }

            @Override
            public Set<Capability> capabilities() {
              return Set.of();
            }

            @Override
            public String id() {
              return "exploding";
            }
          };
      var captured = new ByteArrayOutputStream();
      try (var console =
          Nessy.cli()
              .model(exploding)
              .systemPrompt(TestSettings.SYSTEM_PROMPT)
              .settings(TestSettings.settings())
              .in(new ByteArrayInputStream("hello\n".getBytes(StandardCharsets.UTF_8)))
              .out(new PrintStream(captured, true, StandardCharsets.UTF_8))
              .build()) {
        console.run();
      }
      assertThat(captured.toString(StandardCharsets.UTF_8))
          .contains("turn failed")
          .contains("boom");
    }

    record NoInput() {}

    private static final class GatedTool implements Tool<NoInput> {
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
        return Awaited.ready(ToolResult.ok("restarted"));
      }
    }

    /**
     * The central proof (Task 4's fix): the cli door used to hang {@code ask} forever on an
     * approval-gated tool call, because narration bypassed the per-id fanout — {@code ask}'s own
     * subscribed capture never saw {@code TurnEnded}. It now parks cleanly, the runner routes the
     * ticket to {@link Console#approver()}, and — since answering the console's own scripted "y"
     * resumes the SAME turn — the loop settles on the resumed reply without a second question ever
     * needing to be typed.
     */
    @Test
    void a_parked_turn_routes_to_the_approver_then_settles_on_the_resumed_reply() {
      var call = new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());
      var model =
          new ScriptedModel(
              List.of(
                  List.of(new ModelEvent.ToolUseEmitted(call, null)),
                  List.of(new ModelEvent.TextChunk("restarted, all good"))));
      var captured = new ByteArrayOutputStream();
      // one line of user input, then "y" answering the approval prompt that follows it
      var input = "please restart\ny\n";
      try (var console =
          Nessy.cli()
              .model(model)
              .systemPrompt(TestSettings.SYSTEM_PROMPT)
              .settings(TestSettings.settings())
              .grants(ToolGrant.grant(new GatedTool(), UsagePolicy.requireApproval()))
              .in(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)))
              .out(new PrintStream(captured, true, StandardCharsets.UTF_8))
              .build()) {
        console.run();
      }
      String rendered = captured.toString(StandardCharsets.UTF_8);
      assertThat(rendered).contains("approval requested").contains("approved.");
      assertThat(rendered).contains("restarted, all good");
    }
  }

  @Nested
  class ExactlyOnceNarration {

    /**
     * The fanout composition proof (Task 4's fix): {@code relay}, passed as this harness's global
     * {@code turnObserver}, and a plain {@link org.jwcarman.nessy.agent.Agent#subscribe}d observer
     * both see the SAME turn's {@code AssistantSaid}/{@code TurnEnded} — each exactly once, never
     * twice, neither starving the other.
     */
    @Test
    void an_assistant_reply_reaches_both_a_subscriber_and_the_relay_exactly_once()
        throws Exception {
      var model = new ScriptedModel(List.of(List.of(new ModelEvent.TextChunk("hi there"))));
      try (var console =
          Nessy.cli()
              .model(model)
              .systemPrompt(TestSettings.SYSTEM_PROMPT)
              .settings(TestSettings.settings())
              .build()) {
        List<TurnEvent.AssistantSaid> viaRelay = new CopyOnWriteArrayList<>();
        List<TurnEvent.AssistantSaid> viaSubscriber = new CopyOnWriteArrayList<>();
        console
            .relay()
            .set(
                event -> {
                  if (event instanceof TurnEvent.AssistantSaid said) {
                    viaRelay.add(said);
                  }
                });
        try (var subscription =
            console
                .agent()
                .subscribe(
                    event -> {
                      if (event instanceof TurnEvent.AssistantSaid said) {
                        viaSubscriber.add(said);
                      }
                    })) {
          console.agent().ask("hello");
        }

        assertThat(viaRelay).hasSize(1);
        assertThat(viaSubscriber).hasSize(1);
      }
    }
  }
}
