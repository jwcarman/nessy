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

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.block.Block;

/**
 * The content of one turn.
 *
 * @param input what the turn was given
 * @param requests what the model wrote each time it asked for tool calls, in order
 * @param answer what the model answered, or empty when the turn did not end in an answer
 */
public record TurnContent(
    List<Block.InputContent> input,
    List<RequestContent> requests,
    Optional<List<Block.AnswerContent>> answer) {

  public TurnContent {
    Objects.requireNonNull(input, "input must not be null");
    Objects.requireNonNull(requests, "requests must not be null");
    Objects.requireNonNull(answer, "answer must not be null");
    input = List.copyOf(input);
    requests = List.copyOf(requests);
    answer = answer.map(List::copyOf);
  }
}
