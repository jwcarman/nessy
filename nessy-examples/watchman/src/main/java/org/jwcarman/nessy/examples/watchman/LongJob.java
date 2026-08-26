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
package org.jwcarman.nessy.examples.watchman;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import org.jwcarman.nessy.api.CompletionPolicy;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * {@code long_job} — the one tool here that exists to exercise {@code ToolContext.defer()} for real
 * (spec §2.1).
 *
 * <p>The remediation tools do not need it: an approved {@code systemctl restart} is over in a
 * second, and their waiting is the <i>approval</i>, not the work. A whole-disk {@code fstrim} is
 * different — it can run for minutes, and holding a harness executor thread for minutes is exactly
 * the thing the deferred-tool path exists to avoid.
 *
 * <p>The shape, and why each half of it matters:
 *
 * <ol>
 *   <li>{@code context.defer()} is called <b>on the tool's own thread, before anything is
 *       started</b>. It folds {@code AwaitingResult} and commits before it hands the id back, which
 *       is what makes the next step safe.
 *   <li>The id goes to a watcher on a different executor. The watcher runs the command and then
 *       completes the computation — from a thread that is emphatically not the tool's, which is the
 *       real-world shape (a callback, a webhook, a queue consumer) rather than a convenient
 *       fiction.
 *   <li>The tool returns {@code Awaited.deferred()} — here by way of {@code defers(...)}, whose
 *       whole job is to return it for you.
 * </ol>
 *
 * <p>The completion door is a plain {@link BiConsumer} rather than the {@code CompletionDesk} type
 * itself, and that is the one thing worth arguing about. In the application it is bound to {@code
 * desk::complete} and nothing else; in {@code LongJobTest} it is bound to a queue, which is the
 * only way to assert that <b>the id the tool handed out is the id the desk was told about</b>. The
 * desk is {@code final}, so the alternative was not "test it with a fake desk", it was "do not test
 * it".
 */
public final class LongJob {

  /** No input: there is one long job, and it is a trim of every filesystem. */
  public record Job() {}

  private static final List<String> ARGV = List.of("fstrim", "-av");

  private LongJob() {}

  /**
   * The tool.
   *
   * @param runner the host seam; its {@code run} blocks, which is why it is called on {@code
   *     watchers} and never on the caller's thread
   * @param completions where a finished job is reported — {@code CompletionDesk::complete} in the
   *     application
   * @param watchers the executor the watcher runs on; must not be the harness's own
   */
  public static Tool<Job> tool(
      CommandRunner runner, BiConsumer<ComputationId, ToolResult> completions, Executor watchers) {
    Objects.requireNonNull(runner, "runner must not be null");
    Objects.requireNonNull(completions, "completions must not be null");
    Objects.requireNonNull(watchers, "watchers must not be null");
    return Tool.of(
        Job.class,
        t ->
            t.name("long_job")
                .description(
                    "Starts a whole-disk trim (fstrim -av) in the background. It returns"
                        + " immediately; the result arrives later, in a following turn.")
                .requires(CompletionPolicy.DURABLE)
                .defers((input, context) -> start(context, runner, completions, watchers)));
  }

  private static void start(
      ToolContext context,
      CommandRunner runner,
      BiConsumer<ComputationId, ToolResult> completions,
      Executor watchers) {
    // Ordered by construction: the id exists, and the phase already names the wait, BEFORE any
    // thread that could answer it has been handed anything.
    ComputationId id = context.defer();
    watchers.execute(() -> watch(id, runner, completions));
  }

  private static void watch(
      ComputationId id, CommandRunner runner, BiConsumer<ComputationId, ToolResult> completions) {
    CommandRunner.Output output = runner.run(ARGV);
    completions.accept(id, result(output));
  }

  static ToolResult result(CommandRunner.Output output) {
    String line = String.join(" ", ARGV);
    return output.succeeded()
        ? ToolResult.ok("`" + line + "` finished: " + text(output.stdout()))
        : ToolResult.error(
            "`" + line + "` failed with exit " + output.exitCode() + ": " + text(output.text()));
  }

  private static String text(String value) {
    return value.isBlank() ? "(no output)" : value.strip();
  }
}
