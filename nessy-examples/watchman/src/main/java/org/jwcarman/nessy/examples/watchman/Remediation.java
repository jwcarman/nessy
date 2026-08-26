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
import java.util.function.Function;
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

  private Remediation() {}

  /**
   * A grant that renders {@code argv(input)} as the action, defers to a human, and runs that very
   * argv once approved.
   *
   * @param name the tool's name, as the model sees it
   * @param description what the tool does, written for the model
   * @param inputType the tool's input record
   * @param argv the literal command, derived from the input — rendered for the page AND run
   * @param runner the host seam
   * @param <I> the tool's input type
   */
  static <I> ToolGrant grant(
      String name,
      String description,
      Class<I> inputType,
      Function<I, List<String>> argv,
      CommandRunner runner) {
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(description, "description must not be null");
    Objects.requireNonNull(inputType, "inputType must not be null");
    Objects.requireNonNull(argv, "argv must not be null");
    Objects.requireNonNull(runner, "runner must not be null");
    Tool<I> tool =
        Tool.of(
            inputType,
            t ->
                t.name(name)
                    .description(description)
                    .requires(CompletionPolicy.DURABLE)
                    .executes(input -> outcome(runner, argv.apply(input))));
    ActionContributor<I, String> action =
        ActionContributor.named(name + "-command", input -> commandLine(argv.apply(input)));
    return ToolGrant.grant(tool, action, Approvers.defer());
  }

  /** The literal command line, exactly as it will be executed. */
  static String commandLine(List<String> argv) {
    return String.join(" ", argv);
  }

  private static String outcome(CommandRunner runner, List<String> argv) {
    CommandRunner.Output output = runner.run(argv);
    String line = commandLine(argv);
    return output.succeeded()
        ? "ran `" + line + "`" + suffix(output.stdout())
        : "`" + line + "` failed with exit " + output.exitCode() + suffix(output.text());
  }

  private static String suffix(String text) {
    return text.isBlank() ? "" : ": " + text.strip();
  }
}
