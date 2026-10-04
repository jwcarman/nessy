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
 * Where an agent's story is read.
 *
 * <p>The story is what the agent's stored events say happened, told as the {@link Narrated} a
 * listener hears while it happens. Reading it is the durable counterpart of listening: a listener
 * that was not there, or was there and missed something, reads it here.
 */
@FunctionalInterface
public interface AgentStories {

  /** The story of one agent; an agent with no story has an empty one. */
  AgentStory of(AgentType type, AgentId id);
}
