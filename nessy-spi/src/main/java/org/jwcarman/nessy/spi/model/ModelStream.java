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
package org.jwcarman.nessy.spi.model;

/**
 * One model call, as it happens.
 *
 * <p>Blocking and iterable by design: on virtual threads that is cheaper and far more readable than
 * a callback protocol, and a for-loop over arriving events is what the assembling code wants to be.
 *
 * <p>The caller closes it. A stream abandoned mid-flight holds a connection open, so closing is not
 * hygiene — it is how a provider learns nobody is listening any more.
 */
public interface ModelStream extends Iterable<ModelEvent>, AutoCloseable {

  @Override
  void close();
}
