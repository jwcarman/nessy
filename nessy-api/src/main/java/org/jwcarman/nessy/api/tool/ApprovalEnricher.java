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
 * Adds to the question before anybody answers it.
 *
 * <p>Who the principal is, what the caller's quota looks like, what this tool did the last three
 * times, what a risk model makes of it. Facts a decision might weigh, and might not.
 *
 * <p><b>Gather and judge stay separate</b>, which is the whole reason this is its own type when
 * {@link ApprovalRequest#fact(String, String)} would let any {@link Approver} do both. <b>An
 * enricher never decides.</b> It only makes something available -- so several can run in any order
 * before anything weighs them, a new one can be added without touching the approver, and the same
 * approver can be reused against agents whose questions are enriched differently.
 *
 * <p>It also serves a reader who is not the approver. When a question is deferred, what was
 * gathered here is what a person eventually sees on a page, hours later -- so an enricher is
 * writing evidence for a human as much as input for a rule.
 *
 * <p>Runs on the dispatcher's thread, off the agent's row lock, once per call, so it may do I/O. It
 * runs before the approver and after the action is rendered, so {@link ApprovalRequest#action()} is
 * already there to read.
 *
 * <p><b>Throwing means the call is not approved.</b> There is no arm for "I could not find out": an
 * enricher that cannot gather what it was asked for either records that it could not, as a fact,
 * and lets the approver weigh it -- or throws, and the call is discharged as one that could not be
 * authorised. Quietly contributing nothing is the third option, and it is the one that turns a
 * broken risk service into a silent approval.
 */
@FunctionalInterface
public interface ApprovalEnricher {

  /**
   * @param request the question so far, to be annotated in place
   */
  void enrich(ApprovalRequest request);
}
