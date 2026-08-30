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
 * Content permitted inside a {@link ToolResultBlock}.
 *
 * <p><b>Text only, for now.</b> That is the intersection of what the providers accept: Anthropic
 * takes text and image inside {@code tool_result}, OpenAI takes text alone in a tool-role message.
 * Sticking to the intersection means an image in a tool result is not a promise one provider can
 * keep and another cannot, so there is no capability to negotiate and no adapter left deciding
 * privately whether to drop something.
 *
 * <p><b>Why this stays an interface with one member.</b> Widening a {@code permits} clause is
 * source-compatible for everything that consumes it; changing a {@code List<TextBlock>} to
 * something wider is not. Admitting images later should be a one-word change here, not a signature
 * change everywhere.
 *
 * <p>No provider accepts a thinking block, a tool call, or a nested tool result inside a tool
 * result, so modelling tool output as any wider type would describe a set most of which is illegal,
 * leaving validation to catch at runtime what the compiler can refuse outright. It also closes the
 * recursion a {@link ToolResultBlock} inside a {@link ToolResultBlock} would open.
 */
public sealed interface ToolResultContentBlock extends Block permits TextBlock {}
