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
 * An input that holds nothing. It is for a tool that takes no arguments, and for a harness whose
 * turns are started by a bare nudge.
 *
 * <p><b>For a tool,</b> use it as the tool's input type. The tool offers the model an object with
 * no properties, and the model sends an empty object.
 *
 * <p><b>For a harness,</b> the model still has to be told something, and a harness's default input
 * renderer would send this record's {@code toString}. So a harness of {@code EmptyInput} sets
 * {@code inputRenderer} to say what the nudge means (see {@link InputRenderer}, and {@code
 * inputRenderer} on {@link DirectHarnessConfig} and {@link QueuedHarnessConfig}). It may also set
 * {@code inputLabel}, so the story says what started each turn.
 */
public record EmptyInput() {}
