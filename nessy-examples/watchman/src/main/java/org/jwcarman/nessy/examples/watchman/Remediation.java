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

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jwcarman.nessy.api.CompletionPolicy;
import org.jwcarman.nessy.api.tool.ActionContributor;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.approval.Approvers;

/**
 * The shape every remediation grant in this application shares (spec §2.1): {@link
 * Approvers#defer()} behind an {@link ActionContributor} that renders <b>the exact command line
 * that will run</b>.
 *
 * <p>That rendering is not decoration. It is the string the approval page shows, and it is the
 * whole basis on which a human two days later decides yes or no. Rendering anything other than the
 * literal argv — a paraphrase, a summary, the tool's name — would put a different question on the
 * page from the one being answered. One function derives the argv, and the page and the shell both
 * read it, so the two cannot drift.
 *
 * <p>Each remediation tool is {@link CompletionPolicy#DURABLE}: its approval is expected to outlive
 * the process it was asked in, which is exactly what the soak is measuring.
 */
final class Remediation {

  /**
   * Characters an argument may contain and still be rendered bare. Deliberately conservative:
   * anything outside this set — whitespace, quotes, semicolons, ampersands, globs — gets quoted, so
   * the page can never make a multi-word or shell-active argument look like several plain ones.
   */
  private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._/:@=+-]+");

  private Remediation() {}

  /**
   * The tool half: runs {@code argv(input)} once a human has said yes.
   *
   * @param name the tool's name, as the model sees it
   * @param description what the tool does, written for the model
   * @param inputType the tool's input record
   * @param argv the literal command, derived from the input — rendered for the page AND run
   * @param runner the host seam
   * @param <I> the tool's input type
   */
  static <I> Tool<I> tool(
      String name,
      String description,
      Class<I> inputType,
      Function<I, List<String>> argv,
      CommandRunner runner) {
    return tool(name, description, inputType, argv, runner, null);
  }

  /**
   * The same, with a deadline of its own.
   *
   * <p>{@code null} means "whatever the runner's default is", which is right for the four
   * remediations that finish in a second. {@code apply_updates} passes {@code
   * watchman.upgrade-timeout} instead, because the default budget would SIGKILL dpkg
   * mid-transaction — see {@link CommandRunner#run(List, Duration)}.
   */
  static <I> Tool<I> tool(
      String name,
      String description,
      Class<I> inputType,
      Function<I, List<String>> argv,
      CommandRunner runner,
      Duration timeout) {
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(description, "description must not be null");
    Objects.requireNonNull(inputType, "inputType must not be null");
    Objects.requireNonNull(argv, "argv must not be null");
    Objects.requireNonNull(runner, "runner must not be null");
    return Tool.of(
        inputType,
        t ->
            t.name(name)
                .description(description)
                .requires(CompletionPolicy.DURABLE)
                .executes(input -> outcome(runner, argv.apply(input), timeout)));
  }

  /**
   * The grant half: the same {@code argv} function, rendered as the action, behind {@link
   * Approvers#defer()}. One function, two readers — the page and the shell cannot drift.
   */
  static <I> ToolGrant grant(Tool<I> tool, Function<I, List<String>> argv) {
    Objects.requireNonNull(tool, "tool must not be null");
    Objects.requireNonNull(argv, "argv must not be null");
    ActionContributor<I, String> action =
        ActionContributor.named(tool.name() + "-command", input -> commandLine(argv.apply(input)));
    return ToolGrant.grant(tool, action, Approvers.defer());
  }

  /**
   * The literal command line, exactly as it will be executed — with each argument quoted if it
   * needs quoting to be read back as ONE argument.
   *
   * <p>This is a rendering for a human, not a shell string, and nothing here is ever passed to a
   * shell. But the human is deciding whether to allow a command, and a naive {@code join(" ")}
   * makes that decision on false information: {@code restart_unit("web api")} rendered as {@code
   * systemctl restart web api}, which reads as two units and executes as one whose name contains a
   * space. Quoting closes the gap between what the page says and what the argv means.
   *
   * <p>The quoting is POSIX single-quote form, {@code '} escaped as {@code '\''}, so the rendered
   * line can also be pasted into a shell and do the same thing — useful when someone wants to run
   * it by hand rather than clicking approve.
   */
  static String commandLine(List<String> argv) {
    return argv.stream().map(Remediation::quoted).collect(Collectors.joining(" "));
  }

  private static String quoted(String argument) {
    if (!argument.isEmpty() && SAFE.matcher(argument).matches()) {
      return argument;
    }
    return "'" + argument.replace("'", "'\\''") + "'";
  }

  private static String outcome(CommandRunner runner, List<String> argv, Duration timeout) {
    CommandRunner.Output output = timeout == null ? runner.run(argv) : runner.run(argv, timeout);
    String line = commandLine(argv);
    return output.succeeded()
        ? "ran `" + line + "`" + suffix(output.stdout())
        : "`" + line + "` failed with exit " + output.exitCode() + suffix(output.text());
  }

  private static String suffix(String text) {
    return text.isBlank() ? "" : ": " + text.strip();
  }
}
