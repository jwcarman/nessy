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
package org.jwcarman.nessy.examples;

import java.time.ZonedDateTime;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.ToolResult;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.spi.model.ModelProvider;

/**
 * The one agent definition both example mains share, so the only thing that differs between {@link
 * AnthropicChat} and {@link OpenAiChat} is which provider wires it up.
 */
public final class DemoAgent {

  private static final String SYSTEM_PROMPT =
      "You are Nessy's demo assistant. You can add numbers and tell the current time. Be brief.";

  private DemoAgent() {}

  public static Agent agentFor(ModelProvider provider, String model) {
    return Nessy.agent()
        .provider(provider)
        .model(model)
        .systemPrompt(SYSTEM_PROMPT)
        .tools(new AddTool(), new ClockTool())
        .approver(new ConsoleApprover())
        .build();
  }

  /** Arithmetic, ungated: a tool the model can use freely. */
  record Add(int left, int right) {}

  static final class AddTool implements Tool<Add> {

    @Override
    public String name() {
      return "add";
    }

    @Override
    public String description() {
      return "Adds two integers and returns the sum.";
    }

    @Override
    public Class<Add> inputType() {
      return Add.class;
    }

    @Override
    public boolean requiresApproval() {
      return false;
    }

    @Override
    public String describe(Add input) {
      return input.left() + " + " + input.right();
    }

    @Override
    public Awaited<ToolResult> execute(Add input, ToolContext context) {
      return Awaited.ready(ToolResult.ok(String.valueOf(input.left() + input.right())));
    }
  }

  /**
   * No side effects, no arguments — gated anyway, so every demo run exercises the approval gate.
   */
  record Now() {}

  static final class ClockTool implements Tool<Now> {

    @Override
    public String name() {
      return "clock";
    }

    @Override
    public String description() {
      return "Returns the current date and time.";
    }

    @Override
    public Class<Now> inputType() {
      return Now.class;
    }

    @Override
    public boolean requiresApproval() {
      return true;
    }

    @Override
    public String describe(Now input) {
      return "read the current time";
    }

    @Override
    public Awaited<ToolResult> execute(Now input, ToolContext context) {
      return Awaited.ready(ToolResult.ok(ZonedDateTime.now().toString()));
    }
  }
}
