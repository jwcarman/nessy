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
package org.jwcarman.nessy.examples.chatweb;

import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * The demo shop's compensation tool: obviously consequence-bearing (approval feels natural),
 * obviously harmless (nothing real happens) — a fake confirmation string, nothing more.
 */
public final class IssueCouponTool implements Tool<IssueCouponTool.Input> {

  public record Input(String customerEmail, int amountUsd, String reason) {}

  @Override
  public String name() {
    return "issue_coupon";
  }

  @Override
  public String description() {
    return "Issues a store-credit coupon to a customer. Use when compensation is warranted.";
  }

  @Override
  public Class<Input> inputType() {
    return Input.class;
  }

  @Override
  public Awaited<ToolResult> execute(Input input, ToolContext context) {
    context.progress("issuing…");
    String code = "DEMO-" + Math.abs(input.customerEmail().hashCode() % 10_000);
    return Awaited.ready(
        ToolResult.ok(
            "Coupon "
                + code
                + " for $"
                + input.amountUsd()
                + " issued to "
                + input.customerEmail()
                + " ("
                + input.reason()
                + ")"));
  }
}
