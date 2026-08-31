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
 * What the assistant may say when it is answering.
 *
 * <p>No {@link ToolCallBlock}: an answer asks for nothing, and a turn that is still asking has not
 * answered. That much is ours to decide.
 *
 * <p>{@link ProviderBlock} IS admitted, and that is not ours to decide. A vendor may attach opaque
 * state to a final turn and expect it back — whether reasoning survives an answer is a question
 * about that vendor's protocol, not about our grammar, so the door stays open and each adapter
 * chooses.
 */
public sealed interface AnswerContentBlock extends Block permits TextBlock, ProviderBlock {}
