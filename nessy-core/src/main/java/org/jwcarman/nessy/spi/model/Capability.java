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
 * Something a provider may or may not be able to do.
 *
 * <p>This enum is the anti-rot mechanism for the model seam. A request may <em>ask</em> for prompt
 * caching; a provider that cannot do it says so, and the harness degrades explicitly. Flattening
 * every model to what the weakest one supports is how the 2023-era abstractions died.
 */
public enum Capability {
  THINKING,
  PROMPT_CACHING,
  PARALLEL_TOOL_CALLS,
  IMAGE_INPUT
}
