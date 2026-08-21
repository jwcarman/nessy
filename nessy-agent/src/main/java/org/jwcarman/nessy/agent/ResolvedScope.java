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
package org.jwcarman.nessy.agent;

/**
 * The two operations an {@link AgentResolver} caller needs from a resolved scope's live instance:
 * deliver an event, or re-dispatch outstanding effects. Erasing {@link DefaultAgent}'s observation
 * type here — rather than exposing it as {@code DefaultAgent<?>} — keeps the resolver's contract
 * free of a generic wildcard it has no use for.
 */
public interface ResolvedScope {

  void deliver(AgentEvent event);

  void redispatch();
}
