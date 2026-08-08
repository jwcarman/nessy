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
package org.jwcarman.nessy.approval;

import org.jwcarman.nessy.core.Awaited;
import org.jwcarman.nessy.core.Decision;

/**
 * The safety gate.
 *
 * <p>This is a harness-side interceptor: the model cannot see it, name it, or route around it. That
 * keeps 12-factor's Factor 7 structure while rejecting its trigger. The factor's mechanism — a
 * structured request that is persisted, breaks the loop, and resumes later — is right, and {@link
 * Awaited.Parked} implements it. Its trigger, letting the model decide when to reach a human, is
 * right for clarification and unsafe for approval: a model that never emits the intent simply never
 * asks, and that is indistinguishable from a question that was answered. You cannot put the gate on
 * the far side of the thing it guards.
 *
 * <p>Model-initiated clarification is a separate, ordinary tool.
 *
 * <p>Blocking is fine — an interactive approver parks a virtual thread while a human decides.
 * Return {@link Awaited.Parked} only when the wait must outlive the process.
 */
public interface Approver {

  Awaited<Decision> approve(ApprovalRequest request);
}
