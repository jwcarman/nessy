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

/** What a tool learns about the invocation it is serving. */
public interface ToolContext {

  /**
   * Where an answer should be sent if this tool defers. Available before the tool runs, so a tool
   * can hand it to the outside world and only then return {@link
   * org.jwcarman.nessy.api.Awaited.Deferred}.
   */
  ReplyToken replyToken();
}
