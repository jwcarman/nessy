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
package org.jwcarman.nessy.examples.chatcli;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * The gated tool: sends mail, or would.
 *
 * <p>It sends nothing — an example that could actually mail a stranger is an example nobody can run
 * safely — but it is the right SHAPE for what approval exists to protect: outward-facing,
 * irreversible, and worth a second look. Everything else this program can do is a calculation.
 *
 * <p>The tool knows nothing about approval. Gating is decided where the tool is GRANTED, which is
 * why the same class would be ungated in an application whose policy said so.
 */
final class SendEmailTool implements Tool<SendEmailTool.Input> {

  record Input(
      @JsonPropertyDescription("Recipient address") String to,
      @JsonPropertyDescription("Subject line") String subject,
      @JsonPropertyDescription("Message body") String body) {}

  @Override
  public Class<Input> inputType() {
    return Input.class;
  }

  @Override
  public String name() {
    return "send_email";
  }

  @Override
  public String description() {
    return "Sends an email. A person must approve every send, so say what you intend to send.";
  }

  @Override
  public Awaited<ToolResult> execute(ToolCallRequest<Input> call) {
    Input input = call.input();
    return Awaited.ready(ToolResult.ok("sent to " + input.to()));
  }
}
