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
package org.jwcarman.nessy.examples.toollab;

import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.SystemPrompt;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.turn.Exchange;
import org.jwcarman.nessy.api.turn.Input;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.inference.InferenceContext;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
import org.jwcarman.nessy.inference.Toolset;

/**
 * Asks whether a vendor refuses a request whose history calls a tool the request does not offer.
 * That is the situation the derived rule exists to avoid, so the answer says which vendors enforce
 * it.
 *
 * <p>Two requests share one history: the model called {@code jira_get_issue} and it succeeded. The
 * first offers only {@code slack_post_message}; the second offers nothing. Neither is tried again.
 */
final class Probe {

  private static final String CALLED = "jira_get_issue";
  private static final String OFFERED = "slack_post_message";

  private Probe() {}

  /** Sends the two requests and prints, for each, whether the vendor accepted it. */
  static void run(InferenceProvider provider, InferenceOptions options, PrintStream out) {
    Catalog catalog = Catalog.of(HandWritten.tools().size());
    Turn history = history(catalog);
    Toolset other = Toolset.of(List.of(catalog.find(OFFERED).orElseThrow().offer()));
    out.println("history: the model called " + CALLED + " and the call succeeded");
    report(provider, request(history, other, options), "offering only " + OFFERED, out);
    report(provider, request(history, Toolset.none(), options), "offering no tools", out);
  }

  private static InferenceRequest request(Turn history, Toolset toolset, InferenceOptions options) {
    return new InferenceRequest(
        new SystemPrompt("You are an operations assistant."),
        InferenceContext.of(List.of(history)),
        toolset,
        options);
  }

  private static Turn history(Catalog catalog) {
    Block.ToolCall call = new Block.ToolCall("probe-1", CALLED, "{\"issue_key\":\"AUTH-87\"}");
    CallId id = call.id();
    ToolOutcome outcome =
        new ToolOutcome.Succeeded(
            id,
            List.of(
                new Block.Text(
                    catalog
                        .find(CALLED)
                        .orElseThrow()
                        .run(Schemas.MAPPER.readTree(call.arguments())))));
    Exchange exchange =
        new Exchange(
            new Seq(2),
            List.of(call),
            List.of(outcome),
            Map.of(id, CALLED + "({\"issue_key\":\"AUTH-87\"})"),
            Map.of());
    return new Turn(
        new TurnId(1),
        new Input(new Seq(1), List.of(new Block.Text("What is the status of AUTH-87?"))),
        List.of(exchange),
        null);
  }

  private static void report(
      InferenceProvider provider, InferenceRequest request, String what, PrintStream out) {
    String verdict;
    try {
      InferenceResult result = provider.infer(request);
      verdict =
          switch (result) {
            case InferenceResult.Fault fault -> "rejected: " + fault.failure().reason();
            case InferenceResult.Refusal refusal ->
                "accepted (the model declined: " + refusal.category() + ")";
            case InferenceResult.Answer _ -> "accepted (answered)";
            case InferenceResult.Actions _ -> "accepted (asked for a tool call)";
            case InferenceResult.Truncated _ -> "accepted (reply cut off at the output limit)";
          };
    } catch (RuntimeException thrown) {
      verdict = "rejected: " + thrown.getMessage();
    }
    out.println(what + ": " + verdict);
  }
}
