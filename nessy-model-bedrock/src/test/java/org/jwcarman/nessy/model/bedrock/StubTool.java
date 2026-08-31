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
package org.jwcarman.nessy.model.bedrock;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * A tool that exists only to be DECLARED, never run — the name, description and schema an adapter
 * puts on the wire, and an {@code execute} nothing calls. Replaces the old {@code ToolSpec}, which
 * was exactly this triple as a record.
 */
record StubTool(String name, String description, ObjectNode inputSchema)
    implements Tool<ObjectNode> {

  @Override
  public Class<ObjectNode> inputType() {
    return ObjectNode.class;
  }

  @Override
  public Awaited<ToolResult> execute(ObjectNode input, ReplyToken replyTo) {
    throw new UnsupportedOperationException("a declared-only tool is never executed");
  }
}
