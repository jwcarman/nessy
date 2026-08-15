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
/**
 * The terminal front door, once: three hand-rolled REPLs — {@code chat-cli}'s {@code AnthropicChat}
 * and {@code OpenAiChat}, {@code scout}'s {@code Scout} — paid the same tax three times over,
 * byte-identical {@code ConsoleApprover} included. This module is the one lesson extracted from all
 * three: {@link org.jwcarman.nessy.console.Ansi} for SGR styling, {@link
 * org.jwcarman.nessy.console.ConsoleRenderer} for the default look, a {@code \r} spinner for the
 * wait between send and first token, {@link org.jwcarman.nessy.console.ConsoleRepl} for the loop
 * itself, and {@link org.jwcarman.nessy.console.ConsoleApprover} for the safety gate.
 *
 * <p>Depends on {@code nessy-core} alone. Styling yes, terminal takeover no: SGR codes and a
 * carriage-return spinner, never cursor addressing, raw mode, or an alternate screen — history and
 * completion are a deliberate v2, taken only if JLine is ever pulled in on purpose.
 */
package org.jwcarman.nessy.console;
