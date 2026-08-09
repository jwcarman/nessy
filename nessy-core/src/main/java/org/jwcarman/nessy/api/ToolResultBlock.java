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

import java.util.Objects;

/**
 * What a tool produced, addressed back to the call that asked for it.
 *
 * <p>Carried on a {@link Role#USER} message: the model asked, so the harness answers, and to the
 * model an answer arrives from the user side.
 */
public record ToolResultBlock(String toolUseId, String content, boolean isError)
    implements ContentBlock {

  public ToolResultBlock {
    Objects.requireNonNull(toolUseId, "toolUseId must not be null");
    Objects.requireNonNull(content, "content must not be null");
  }
}
