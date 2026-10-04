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
 * What is known about a failed model call, which is what decides whether trying again could help.
 *
 * <p>Not degrees of severity. A watcher told a turn failed wants to know whether it is worth asking
 * again, and that is a statement about what is known rather than about how bad it was.
 */
public enum FailureKind {
  /** It failed, and it might not next time. */
  TRANSIENT,
  /** Nobody found out whether it failed. */
  UNKNOWN,
  /** It failed, and the identical request will fail identically. */
  PERMANENT,
  /** The provider rejected the input itself, and named it. */
  REJECTED
}
