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
package org.jwcarman.nessy.core;

import java.util.Objects;

/**
 * What a tool produced.
 *
 * <p>{@code isError} is the factor-9 hinge: an errored result still flows into context so the model
 * can recover, rather than blowing up the loop.
 */
public record ToolResult(String content, boolean isError) {

  public ToolResult {
    Objects.requireNonNull(content, "content must not be null");
  }

  public static ToolResult ok(String content) {
    return new ToolResult(content, false);
  }

  public static ToolResult error(String content) {
    return new ToolResult(content, true);
  }
}
