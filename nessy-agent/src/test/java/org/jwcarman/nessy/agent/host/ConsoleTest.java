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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
      // the console observer streams TextDelta live (fix round 2, M9), so "hello back" appears
      // once as it streams and again as render()'s final settled line — contains, not equals.
      assertThat(captured.toString(StandardCharsets.UTF_8)).contains("hello back");
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

  @Nested
  class Lifecycle {

    /**
     * Migrated from the retired {@code CliAgentTest} (fix round 1, finding 2a); repointed at the
     * shared {@code ComputationScheduler} pool (continuum-adoption spec §7, replacing the per-
     * harness daemon heartbeat this test used to watch): {@code Nessy.cli()}'s build runs its
     * {@link org.jwcarman.nessy.agent.Harness} through the same compiler every door shares, so it
     * builds its own scheduler exactly like any other harness's — {@link Console#close()} must
     * quiesce it, or the ephemeral-CLI charter (one turn, then gone) is violated by stranded daemon
     * threads. Enumerated by name prefix rather than a new public seam: every thread {@code
     * ComputationScheduler} runs is named {@code "nessy-pump-<n>"}.
     *
     * <p>Two "do not pin tuning as contract" concessions (spec §7's own ruling, extended to thread
     * count and start timing): (1) pool threads now start lazily, on first scheduled task fire —
     * this class dropped an earlier eager {@code prestartAllCoreThreads()} (fix round 1 item 3), so
     * the "started" side polls up to the pumps' own fastest initial delay rather than asserting
     * immediately after {@link Console#run()}; (2) the pool size itself is tuning, so both sides
     * compare against {@code before} with an inequality, never an exact {@code + N} or {@code ==} —
     * a harness some OTHER, concurrently-running test shut down mid-window could otherwise drive
     * the live count below {@code before} and fail an exact-equality check that was never this
     * test's to make (fix round 1 item 7).
     */
    @Test
    void closing_the_console_leaves_no_live_delivery_pump_thread() throws InterruptedException {
      var model = new ScriptedModel(List.of(List.of(new ModelEvent.TextChunk("hello back"))));
      long before = liveDeliveryThreadCount();

      var captured = new ByteArrayOutputStream();
      var console =
          Nessy.cli()
              .model(model)
              .systemPrompt(TestSettings.SYSTEM_PROMPT)
              .settings(TestSettings.settings())
              .in(new ByteArrayInputStream("hello\n".getBytes(StandardCharsets.UTF_8)))
              .out(new PrintStream(captured, true, StandardCharsets.UTF_8))
              .build();
      console.run();
      // the console observer streams TextDelta live (fix round 2, M9); contains, not equals.
      assertThat(captured.toString(StandardCharsets.UTF_8)).contains("hello back");

      // the fastest pump (deliver) has a 1s initial delay, so the pool's threads may not have
      // started yet the instant run() returns — poll rather than assert immediately.
      long startDeadline = System.currentTimeMillis() + 3000;
      long started = liveDeliveryThreadCount();
      while (started <= before && System.currentTimeMillis() < startDeadline) {
        Thread.sleep(20);
        started = liveDeliveryThreadCount();
      }
      assertThat(started).isGreaterThan(before);

      console.close();

      // ScheduledExecutorService#shutdown() lets running/queued tasks finish before its threads
      // actually die — give the pool a moment to actually stop.
      long closeDeadline = System.currentTimeMillis() + 2000;
      long after = liveDeliveryThreadCount();
      while (after > before && System.currentTimeMillis() < closeDeadline) {
        Thread.sleep(20);
        after = liveDeliveryThreadCount();
      }
      assertThat(after).isLessThanOrEqualTo(before);
    }

    private static long liveDeliveryThreadCount() {
      return Thread.getAllStackTraces().keySet().stream()
          .filter(Thread::isAlive)
          .filter(t -> t.getName().startsWith("nessy-pump-"))
          .count();
    }

    /**
     * Migrated from the retired {@code CliAgentTest} (fix round 1, finding 2b): {@link
     * Console#close()}'s {@code ownsExecutor} conditional is console-specific and was untested —
     * this must fail against a flipped {@code if}. A caller-supplied executor is never the
     * console's to close.
     */
    @Test
    void a_caller_supplied_executor_survives_console_close() {
      ExecutorService callerExecutor = Executors.newVirtualThreadPerTaskExecutor();
      var model = new ScriptedModel(List.of(List.of(new ModelEvent.TextChunk("hi"))));
      try (var console =
          Nessy.cli()
              .model(model)
              .systemPrompt(TestSettings.SYSTEM_PROMPT)
              .settings(TestSettings.settings())
              .executor(callerExecutor)
              .in(new ByteArrayInputStream("hello\n".getBytes(StandardCharsets.UTF_8)))
              .out(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
              .build()) {
        console.run();
      }
      assertThat(callerExecutor.isShutdown()).isFalse();
      callerExecutor.close();
    }
  }

  @Nested
  class LiveStreaming {

    /**
     * The spec §3 console observer, delivered (fix round 2, M9): {@code relay} streams every {@code
     * TextDelta} to {@code out} live, flushed per delta, the instant it narrates — well before
     * {@code render()} prints the turn's final, settled {@code Replied} line off the returned
     * {@link org.jwcarman.nessy.agent.TurnOutcome}. Two scripted chunks concatenate to the same
     * text as the final line, so the streamed prefix and the final line are distinguishable only by
     * position and the trailing newline {@code render()} alone adds.
     */
    @Test
    void a_scripted_turns_deltas_appear_on_out_before_the_final_replied_line() {
      var model =
          new ScriptedModel(
              List.of(List.of(new ModelEvent.TextChunk("Hel"), new ModelEvent.TextChunk("lo!"))));
      var captured = new ByteArrayOutputStream();
      try (var console =
          Nessy.cli()
              .model(model)
              .systemPrompt(TestSettings.SYSTEM_PROMPT)
              .settings(TestSettings.settings())
              .in(new ByteArrayInputStream("hi\n".getBytes(StandardCharsets.UTF_8)))
              .out(new PrintStream(captured, true, StandardCharsets.UTF_8))
              .build()) {
        console.run();
      }
      // "Hel" + "lo!" streamed live (no newline, one flush each), then "Hello!\n" as render()'s
      // own, separate final line — the deltas are unmistakably BEFORE it, not folded into it.
      assertThat(captured.toString(StandardCharsets.UTF_8)).isEqualTo("Hello!Hello!\n");
    }
  }
}
