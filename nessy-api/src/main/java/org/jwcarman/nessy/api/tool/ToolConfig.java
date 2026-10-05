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
package org.jwcarman.nessy.api.tool;

import java.time.Duration;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.RetryPolicy;
import org.jwcarman.nessy.api.Stringifier;
import org.jwcarman.nessy.api.block.Block;

/**
 * How one tool is bound to an agent type: how long a call may take, how hard to try it, and who may
 * say no.
 *
 * <p>These are the application's statements ABOUT the tool, never the tool's about itself -- which
 * is what makes a third-party tool governable without wrapping it in a class.
 *
 * @param <I> the tool's bound input
 */
public interface ToolConfig<I> {

  /**
   * The most characters a recorded line may have, whatever the application's stringifier says. It
   * also bounds a failed call's message.
   */
  int LINE_CAP = 1000;

  /** The most characters a recorded line may have when the application named no stringifier. */
  int DEFAULT_LINE_LIMIT = 255;

  /**
   * How long a call of this tool is worth waiting for. Reaches the tool as {@link
   * ToolCallRequest#deadline()}.
   */
  ToolConfig<I> timeout(Duration timeout);

  /**
   * How hard a failed call of this tool is worth trying again.
   *
   * <p>Defaults to {@link RetryPolicy.Never}, and for most tools that is the right answer
   * permanently: an attempt whose outcome was never observed may well have run, and a tool that
   * changed the world outside the agent must be reconciled rather than repeated. Widen this only
   * for a tool you know to be idempotent.
   */
  ToolConfig<I> retryPolicy(RetryPolicy retryPolicy);

  /**
   * What a call of this tool would actually do, in a line a person can read and consent to.
   *
   * <p>Named for what it produces -- {@link ApprovalRequest#action()} -- and not for rendering,
   * because a {@link Tool} already has a description and it means something else: what the tool IS,
   * written for the model. This is what one call, with these arguments, would actually do.
   *
   * <p>Four readers, none of them the model that made the call: an approvals page, where a person
   * cannot consent to {@code {"customer_id":"cus_8823","op":"purge"}} but can consent to
   * "permanently delete Acme Corp's record"; a UI narrating tool use; a log line; and the model
   * that summarises a chapter, which reads the line stored in the event.
   *
   * <p>Defaults to the input's own {@code toString()}, which reads well for a record and badly for
   * anything else. It prints every component, so a field holding a credential or a customer's email
   * reaches every one of those readers, and a bounded copy of the line is stored in the event and
   * may be quoted in a summary. Write one for any tool a person will be asked to approve.
   *
   * <p>The line is made one line -- every run of whitespace, line breaks included, becomes a single
   * space -- and then cut, so a sentence written with line breaks reaches an approver without them.
   * Whatever is named is cut to {@link #LINE_CAP} characters, and one that writes more is cut by
   * {@link Stringifier#dropTail}. A stringifier that already drops at or below the cap is used as
   * given. With none named a line is cut at {@link #DEFAULT_LINE_LIMIT}, keeping its start. A tool
   * that is gated should have a sentence short enough not to be cut, or name {@code dropTail}
   * itself, so the person approving sees the part that matters.
   *
   * <p>A stringifier that throws never fails the turn. The call is recorded as "what it would do
   * could not be said", and a gated call so recorded is refused without asking an approver: there
   * is no sentence to consent to. One that gives nothing records the tool's name, and that is what
   * an approver is shown.
   *
   * <p><b>It lives on the binding, never on the {@link Tool}.</b> If the sentence a person approves
   * against were authored by the tool being governed -- an MCP server, say -- it would not be a
   * control. The application states what a call means, per tool it offers.
   */
  ToolConfig<I> action(Stringifier<I> action);

  /**
   * What a call of this tool returned, in a line, for the transcript and for the summaries written
   * from it.
   *
   * <p>Defaults to {@link #resultText()}. The line is made one line -- every run of whitespace,
   * line breaks included, becomes a single space -- and then cut. Whatever is named is cut to
   * {@link #LINE_CAP} characters, and one that writes more is cut by {@link
   * Stringifier#dropMiddle}. A stringifier that already drops at or below the cap is used as given.
   * With none named a line is cut at {@link #DEFAULT_LINE_LIMIT}, keeping both ends. A result that
   * cannot be said is recorded as an empty line; it never fails the turn.
   */
  ToolConfig<I> result(Stringifier<ToolResult.Success> result);

  /**
   * The text of a result: its text blocks, joined by a space. Blocks that are not text are skipped.
   */
  static Stringifier<ToolResult.Success> resultText() {
    return success -> {
      StringBuilder joined = new StringBuilder();
      for (Block.ToolResultContent block : success.blocks()) {
        if (block instanceof Block.Text(String text)) {
          if (!joined.isEmpty()) {
            joined.append(' ');
          }
          joined.append(text);
        }
      }
      return joined.toString();
    };
  }

  /**
   * Adds something to the approval request before the approver sees it. May be called more than
   * once; they run in the order they were added.
   *
   * <p>Separate from {@link #approver} because gathering and deciding are separate jobs: an
   * enricher never says no, it only makes a fact available. So a risk score, a resolved principal
   * and a quota check can be added independently of each other and of whatever eventually weighs
   * them.
   */
  ToolConfig<I> enrich(ApprovalEnricher enricher);

  /** Who decides whether a call of this tool may run. Defaults to {@link Approver#allow()}. */
  ToolConfig<I> approver(Approver approver, Customizer<ApproverConfig> customizer);

  default ToolConfig<I> approver(Approver approver) {
    return approver(approver, Customizer.withDefaults());
  }
}
