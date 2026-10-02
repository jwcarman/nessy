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
package org.jwcarman.nessy.engine.tool;

import java.util.Optional;
import org.jwcarman.nessy.api.Stringifier;
import org.jwcarman.nessy.api.tool.ToolConfig;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * Settles what a binding said about its action and result lines into the two stringifiers a {@link
 * ToolBinding} holds, each already wrapped so no line can overflow.
 *
 * <p>Both harness doors settle the same way, so it is written once, here.
 *
 * <table>
 *   <caption>What a binding gets</caption>
 *   <tr><th>The binding</th><th>Action</th><th>Result</th></tr>
 *   <tr><td>names none</td><td>the input's {@code toString()}, {@code dropTail(255)}</td>
 *       <td>the result's text, {@code dropMiddle(255)}</td></tr>
 *   <tr><td>names one</td><td>that one, {@code dropTail(1000)}</td>
 *       <td>that one, {@code dropMiddle(1000)}</td></tr>
 * </table>
 *
 * <p>A named stringifier that already drops at or below 1000 comes back as the same instance, so it
 * is used exactly as its author cut it.
 */
final class SettledLines {

  private SettledLines() {}

  /** The action stringifier a binding holds. */
  static <I> Stringifier<I> action(Optional<Stringifier<I>> named) {
    return named
        .map(action -> action.dropTail(ToolConfig.LINE_CAP))
        .orElseGet(() -> Stringifier.<I>byToString().dropTail(ToolConfig.DEFAULT_LINE_LIMIT));
  }

  /** The result stringifier a binding holds. */
  static Stringifier<ToolResult.Success> result(Optional<Stringifier<ToolResult.Success>> named) {
    return named
        .map(result -> result.dropMiddle(ToolConfig.LINE_CAP))
        .orElseGet(() -> ToolConfig.resultText().dropMiddle(ToolConfig.DEFAULT_LINE_LIMIT));
  }
}
