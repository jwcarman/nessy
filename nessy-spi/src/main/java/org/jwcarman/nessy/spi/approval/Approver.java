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
package org.jwcarman.nessy.spi.approval;

/**
 * What a wiring does when the policy says RequireApproval (spec §4.3 amendment): the rendezvous
 * approver blocks a human-present channel; the slot-backed approver suspends into the durable
 * backend; the default refuses loudly in-band — approval is a capability of the wiring, not a right
 * of every deployment.
 */
@FunctionalInterface
public interface Approver {
  Adjudication adjudicate(ApprovalRequest request);
}
