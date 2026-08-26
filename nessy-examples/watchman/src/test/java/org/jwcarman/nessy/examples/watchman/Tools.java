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

import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolResult;

/** Running one tool and reading what it said, without a harness in the way. */
final class Tools {

  private Tools() {}

  /** The content of a tool's ready result; a failure if the tool deferred instead. */
  static <I> String content(Tool<I> tool, I input) {
    return switch (tool.execute(input, new FakeContext())) {
      case Awaited.Ready<ToolResult> ready -> ready.value().content();
      case Awaited.Deferred<ToolResult> deferred ->
          throw new AssertionError(tool.name() + " deferred; a ready result was expected");
    };
  }
}
