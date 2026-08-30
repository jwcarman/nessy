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
 * Content permitted in a {@code UserMessage}.
 *
 * <p>Named for its container rather than for a category, because the set has no intrinsic meaning:
 * what unites these blocks is not something about them, it is that providers accept them here.
 * Every marker in this package follows the same rule, so each one reads true — a {@link TextBlock}
 * <i>is</i> user content.
 */
public sealed interface UserContentBlock extends Block permits TextBlock, ImageBlock {}
