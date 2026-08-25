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
package org.jwcarman.nessy.api.tool.approval;

/** The rules every ladder ends with. */
public final class Rules {

  private Rules() {}

  public static Rule allow() {
    return Rule.named("allow", request -> new Rule.Verdict.Answered(Approval.approved()));
  }

  public static Rule deny(String reason) {
    Approval denied = Approval.denied(reason);
    return Rule.named("deny", request -> new Rule.Verdict.Answered(denied));
  }

  public static Rule defer() {
    return Rule.named("defer", request -> new Rule.Verdict.Defer());
  }

  public static Rule undecided() {
    return Rule.named("undecided", request -> new Rule.Verdict.Undecided());
  }
}
