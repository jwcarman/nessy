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
import org.jwcarman.nessy.api.block.Block;

/**
 * What the model wrote when it asked for tool calls: its commentary and the calls themselves.
 *
 * @param seq the position of the story event that recorded the request
 * @param blocks what the model wrote, in order
 */
public record RequestContent(Seq seq, List<Block.ActionRequestContent> blocks) {

  public RequestContent {
    Objects.requireNonNull(seq, "seq must not be null");
    Objects.requireNonNull(blocks, "blocks must not be null");
    blocks = List.copyOf(blocks);
  }
}
