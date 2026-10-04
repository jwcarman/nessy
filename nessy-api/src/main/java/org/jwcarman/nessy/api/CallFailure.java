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
 * Why a call did not produce a result, when nobody refused it.
 *
 * <p>A refusal is its own event, {@link Narration.CallDenied}. This says which of three other
 * things happened, so a reader can count them without matching text.
 */
public enum CallFailure {
  /** The tool ran and failed, or could not be run at all. */
  FAILED,
  /** The call did not finish before its deadline, and whether it ran is not known. */
  PAST_DEADLINE,
  /** Permission was never given: the approval's deadline passed, or the approver itself failed. */
  NOT_AUTHORISED
}
