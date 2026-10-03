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
package org.jwcarman.nessy.api;

/**
 * How much each agent has used, read from its stored history.
 *
 * <p>A projection over the agent's events, not a counter kept beside them: every inference the
 * engine recorded is counted, whether it answered, asked for actions, was refused, failed, or was
 * retried, and the report is the same however often it is read and after any restart. That is what
 * makes it fit for cost accounting, where narration, which is announced once and may be missed, is
 * not.
 */
@FunctionalInterface
public interface UsageReports {

  /** The agent's usage over its whole history; empty for an agent with no story. */
  UsageReport of(AgentType type, AgentId id);
}
