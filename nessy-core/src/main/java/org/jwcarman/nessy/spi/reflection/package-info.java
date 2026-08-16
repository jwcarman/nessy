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
/**
 * The critic: the generation half of reflection (design of record 2026-08-16 §3). {@link
 * org.jwcarman.nessy.spi.reflection.Reflection#critic} builds a listener for {@link
 * org.jwcarman.nessy.api.ConversationSettled} that reviews the settled transcript with a side model
 * call and writes distilled lessons into the subject's {@link
 * org.jwcarman.nessy.spi.notebook.Notebook}, mirroring how {@code org.jwcarman.nessy.spi.notebook}
 * holds the injection half.
 */
package org.jwcarman.nessy.spi.reflection;
