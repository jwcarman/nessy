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
package org.jwcarman.nessy.inference.anthropic;

/**
 * Whether this provider marks the stable prefix of every request for Anthropic's prompt cache, and
 * for how long the cache keeps it.
 *
 * <p>A deployment decision rather than a per-call one: a cache write costs more than an ordinary
 * token, so it pays for an agent whose system prompt and tools are long and repeated round after
 * round, and is a bill for nothing for an agent that never repeats a prefix. An application with
 * both kinds builds a provider for each.
 */
public enum PromptCaching {
  OFF,
  FIVE_MINUTES,
  ONE_HOUR
}
