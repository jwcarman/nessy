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
package org.jwcarman.nessy.api.block;

/**
 * Content permitted in an {@code AssistantMessage}.
 *
 * <p>The assistant is the only role whose content set is unusual — reasoning and tool calls are
 * things only a model produces — which is why this marker earns its keep where a plain content
 * marker would not.
 */
public sealed interface AssistantContentBlock extends Block
    permits TextBlock, ThinkingBlock, RedactedThinkingBlock, ToolCallBlock {}
