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
 * A live subscription to one agent's narration. Close it to stop listening.
 *
 * <p>{@code close()} declares no checked exception, unlike {@link AutoCloseable#close()}: stopping
 * a subscription cannot fail in a way a caller could act on, and a {@code throws Exception} would
 * put a catch block at every call site for something that never happens.
 *
 * <p>Dropping one unclosed leaks a routing entry, never a thread.
 */
public interface AgentSubscription extends AutoCloseable {

  @Override
  void close();
}
