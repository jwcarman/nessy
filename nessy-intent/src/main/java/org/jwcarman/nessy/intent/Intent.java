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
package org.jwcarman.nessy.intent;

/**
 * The model's own untrusted claim of what it is about to do and why (authorization design §7): a
 * declaration, recorded before the model calls any other tool, that a policy may read back through
 * {@code AuthzContext.declaredIntent()} — never a grant of authority on its own, only a claim an
 * enricher deposits and a policy may weigh alongside everything else it gathers.
 *
 * @param declaration what the model says it is about to do and why, never blank
 */
public record Intent(String declaration) {

  public Intent {
    if (declaration == null || declaration.isBlank()) {
      throw new IllegalArgumentException("declaration must not be blank");
    }
  }
}
