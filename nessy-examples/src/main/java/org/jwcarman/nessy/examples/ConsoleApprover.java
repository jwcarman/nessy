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

import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.approval.ApprovalRequest;
import org.jwcarman.nessy.api.approval.Approver;

/** The safety gate, worked by a human at the keyboard: prints the request, reads y/n. */
final class ConsoleApprover implements Approver {

  @Override
  public Awaited<Decision> approve(ApprovalRequest request) {
    IO.println("approve: " + request.description());
    String answer = IO.readln("y/n> ");
    // IO.readln returns null at EOF (e.g. Ctrl-D on the console); treated as a denial, same as
    // any other non-"y" answer, rather than NPE-ing on the trim() below.
    return answer != null && answer.trim().equalsIgnoreCase("y")
        ? Awaited.ready(Decision.allow())
        : Awaited.ready(new Decision.Deny("declined at the console"));
  }
}
