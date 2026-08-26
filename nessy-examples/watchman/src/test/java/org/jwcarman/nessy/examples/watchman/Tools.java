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

import java.lang.reflect.Constructor;
import java.util.Arrays;
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

  /**
   * Runs a tool without the caller having to know its input type — for tests that care about what
   * the tool ASKED the host for rather than what it answered.
   *
   * <p>The input is built reflectively from the record's canonical constructor: {@code String}
   * components get a harmless placeholder, everything else gets {@code null}, which every input
   * record here reads as "not specified" and replaces with its own default. The generic parameter
   * plus {@code inputType().cast} is what lets this take a {@code Tool<?>} with no unchecked cast
   * and no suppression.
   */
  static <I> void runWithPlaceholderInput(Tool<I> tool) {
    tool.execute(tool.inputType().cast(placeholderInput(tool.inputType())), new FakeContext());
  }

  private static Object placeholderInput(Class<?> inputType) {
    Constructor<?> canonical = inputType.getDeclaredConstructors()[0];
    Object[] arguments =
        Arrays.stream(canonical.getParameterTypes())
            .map(parameter -> parameter == String.class ? "placeholder" : null)
            .toArray();
    try {
      canonical.setAccessible(true);
      return canonical.newInstance(arguments);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("could not build a placeholder " + inputType.getName(), e);
    }
  }
}
