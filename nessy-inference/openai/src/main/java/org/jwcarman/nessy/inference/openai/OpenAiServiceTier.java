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
package org.jwcarman.nessy.inference.openai;

/**
 * The processing tier a request asks for, as the value of {@code openai.service_tier}. {@link
 * #ULTRAFAST} is carried by the openai-responses wire only; the openai-chat wire refuses it at
 * build.
 */
public enum OpenAiServiceTier {
  AUTO,
  DEFAULT,
  FLEX,
  SCALE,
  PRIORITY,
  FAST,
  ULTRAFAST;
}
