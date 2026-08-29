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
package org.jwcarman.nessy.api.message;

/**
 * The content a tool is allowed to hand back: text, or an image.
 *
 * <p>A narrower {@link ContentBlock}, and narrower on purpose. No provider accepts a thinking
 * block, a tool-use block, or a nested tool result inside a tool result — so modelling tool output
 * as {@code List<ContentBlock>} would describe a set three-quarters of which is illegal, leaving
 * validation to catch at runtime what the compiler can refuse outright. It also closes the
 * recursion a {@code ToolResultBlock} inside a {@code ToolResultBlock} would open.
 *
 * <p>{@link TextBlock} and {@link ImageBlock} remain {@link ContentBlock}s through this interface,
 * so every existing use of them is untouched.
 */
public sealed interface ResultBlock extends ContentBlock permits TextBlock, ImageBlock {}
