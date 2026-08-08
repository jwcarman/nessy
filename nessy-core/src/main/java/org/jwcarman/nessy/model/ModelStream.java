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
package org.jwcarman.nessy.model;

/**
 * One turn's worth of streamed events.
 *
 * <p>An {@code Iterable} rather than a publisher: on virtual threads, blocking iteration is the
 * cheap and readable option, and it maps directly onto what the Anthropic and OpenAI Java SDKs
 * already hand you. {@code close()} narrows {@link AutoCloseable} to drop the checked exception so
 * try-with-resources at the call site stays clean.
 */
public interface ModelStream extends Iterable<ModelEvent>, AutoCloseable {

  @Override
  void close();
}
