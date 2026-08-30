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
 * Something a provider may be able to do, that a caller may want used.
 *
 * <p>Asked for, never assumed: a request states what it would LIKE, and a provider that cannot
 * oblige simply does not. That way one harness configuration runs against several vendors without
 * the caller branching on which one it got.
 *
 * <p>Lives in the SPI rather than the API because it is a fact about providers. An application
 * names a model and says what it wants; what any particular vendor can actually do is between the
 * request and the adapter.
 */
public enum Capability {

  /** The model can reason visibly before answering. */
  THINKING,

  /** Repeated prefixes can be cached, so a long standing prompt is not paid for every turn. */
  PROMPT_CACHING,

  /** Prefix caching with a longer retention than the provider default. */
  PROMPT_CACHING_1H,

  /** Several tool calls may be asked for in one reply. */
  PARALLEL_TOOL_CALLS,

  /** Images may appear in what is sent. */
  IMAGE_INPUT
}
