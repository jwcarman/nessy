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
 * Spring Boot auto-configuration for Nessy (watchman spec §1): a durable, observed harness wired
 * from an application's beans, plus the pending-approvals projection every Boot application that
 * parks approvals turns out to need.
 *
 * <p>Five public types and no new vocabulary. {@code NessyAutoConfiguration} composes what {@code
 * Nessy.harness(...)} already offers; {@code NessyProperties} names the handful of knobs that are
 * configuration rather than code; {@code PendingApprovals}, {@code PendingApprovalsRepository} and
 * {@code PendingApproval} are the projection the fact stream feeds. Tools, grants and approvers are
 * beans, because they are code.
 */
package org.jwcarman.nessy.spring.boot;
