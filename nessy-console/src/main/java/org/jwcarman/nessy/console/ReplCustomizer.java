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
package org.jwcarman.nessy.console;

/**
 * Fills in a {@link ReplConfig}. A functional interface so the common case is a lambda, and named
 * rather than {@code Consumer<ReplConfig>} so the type says what it is for.
 */
@FunctionalInterface
public interface ReplCustomizer {

  void customize(ReplConfig config);
}
