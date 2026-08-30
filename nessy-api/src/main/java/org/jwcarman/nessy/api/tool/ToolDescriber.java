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

/**
 * Says what a call would do, in a sentence a person can read.
 *
 * <p>Three readers: an approval page, where a human cannot consent to {@code
 * {"customer_id":"cus_8823","op":"purge"}} but can consent to "permanently delete Acme Corp's
 * record"; a chat UI narrating tool use; and a log line.
 *
 * <p><b>It lives on the binding, never on the {@link Tool}.</b> If the sentence a human approves
 * against were authored by the tool being governed — an MCP server, say — it would not be a
 * control. The application states what the call means, per tool it grants.
 *
 * @param <I> the tool's bound input
 */
@FunctionalInterface
public interface ToolDescriber<I> {

  String describe(I input);

  /**
   * The default: the input's own {@code toString()}.
   *
   * <p>Good enough for a record — {@code RefundOrder[orderId=ord_88, amountCents=4200]} reads well
   * — and null-safe. Two things it does not do: an input that is not a record renders as {@code
   * com.acme.PurgeRequest@1a2b3c}, and every component is printed, so a field holding a credential
   * or a customer's email reaches whoever is reading. Write a real describer for anything a person
   * will be asked to approve.
   */
  static <I> ToolDescriber<I> byToString() {
    return String::valueOf;
  }
}
