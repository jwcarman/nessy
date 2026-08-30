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
package org.jwcarman.nessy.api.model;

/**
 * Why a model turn stopped — for turns that produced a reply.
 *
 * <p>There is no {@code REFUSAL} constant, deliberately. A refusal is not a reason a reply ended;
 * it is a different kind of outcome, with data no reply has and possibly no content at all, so it
 * is {@link ModelResult.Refused} instead. Keeping it out of here means a consumer reading a reply's
 * stop reason is never holding an empty message it forgot to check for.
 */
public enum StopReason {

  /** The model finished what it had to say. */
  END_TURN,

  /** The model wants tools run before it continues. */
  TOOL_USE,

  /** The output ceiling was reached; the content is cut off mid-thought. */
  MAX_TOKENS
}
