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

/**
 * A facade in the way {@code Memory} is (approval-lifecycle spec §1.3): one method, and a world
 * behind it — a rule ladder, a risk service, a Slack post, a policy engine, a quorum, a person at a
 * terminal — none of it visible to the harness, and all of it free to be asynchronous through
 * {@link ApprovalContext#defer()}. An approver either answers or says it will get back to us; it
 * never sees Continuum, a kind, a continuation or a lease. Telling people is its business: whatever
 * it does after {@code defer()} hands it an id is how the human learns there is a question.
 *
 * <p>Approvers are at-least-once, like tools: a re-fired call asks again. A rule ladder is free; a
 * service is called twice; a console prompt re-asks its human.
 */
@FunctionalInterface
public interface Approver {

  ApprovalOutcome approve(ApprovalContext context);
}
