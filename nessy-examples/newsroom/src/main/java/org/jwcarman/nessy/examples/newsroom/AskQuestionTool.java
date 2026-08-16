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
package org.jwcarman.nessy.examples.newsroom;

import java.util.Objects;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * The one tool this demo grants {@link org.jwcarman.nessy.api.tool.UsagePolicy#requireApproval()}:
 * the researcher's escape hatch when a delegated task is ambiguous. Nothing about {@code execute}
 * itself parks — the gate does, via {@link org.jwcarman.nessy.api.approval.Approver#parkAll()},
 * before this tool is ever invoked (see {@link NewsroomAgents}) — which is exactly why the child
 * (researcher) conversation parks, and, one level up, why the writer's own delegation tool call
 * parks with it (spec's park-chain: a subagent call is a tool call, so its child parking is
 * indistinguishable, from the parent's own loop, from any other tool that parks).
 *
 * <p>{@code execute} only ever runs after the gate has already let the call through — an operator
 * answered "yes" at the console and {@link PendingAnswers#record} ran first — so the answer is
 * always on file by the time this reads it back.
 */
final class AskQuestionTool implements Tool<AskQuestionTool.AskQuestion> {

  private final PendingAnswers answers;

  AskQuestionTool(PendingAnswers answers) {
    this.answers = Objects.requireNonNull(answers, "answers must not be null");
  }

  /** What the model supplies: the question it wants a human to answer. */
  record AskQuestion(String question) {

    AskQuestion {
      if (question == null || question.isBlank()) {
        throw new IllegalArgumentException("question must not be blank");
      }
    }
  }

  @Override
  public String name() {
    return "ask_question";
  }

  @Override
  public String description() {
    return "Asks the newsroom editor a clarifying question when a research task is ambiguous."
        + " Pauses until a human answers.";
  }

  @Override
  public Class<AskQuestion> inputType() {
    return AskQuestion.class;
  }

  @Override
  public String effect(AskQuestion input) {
    return input.question();
  }

  @Override
  public Awaited<ToolResult> execute(AskQuestion input, ToolContext context) {
    return Awaited.ready(ToolResult.ok(answers.take(context.call().id())));
  }
}
