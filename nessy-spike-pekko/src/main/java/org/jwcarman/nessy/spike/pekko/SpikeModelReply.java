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
package org.jwcarman.nessy.spike.pekko;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

/** THROWAWAY SPIKE. What a model turn can say: a sentence, or a request for tools. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "reply")
@JsonSubTypes({
  @JsonSubTypes.Type(value = SpikeModelReply.Said.class, name = "said"),
  @JsonSubTypes.Type(value = SpikeModelReply.AskedForTools.class, name = "asked-for-tools")
})
public sealed interface SpikeModelReply extends SpikeSerializable {

  record Said(String text) implements SpikeModelReply {}

  record AskedForTools(List<Request> requests) implements SpikeModelReply {
    public AskedForTools {
      requests = List.copyOf(requests);
    }
  }

  /** One requested call, before the harness has decided whether it needs an approval. */
  record Request(String id, String tool, String argument) implements SpikeSerializable {}
}
