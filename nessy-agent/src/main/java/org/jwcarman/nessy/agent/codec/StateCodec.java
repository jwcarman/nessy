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
package org.jwcarman.nessy.agent.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jwcarman.nessy.agent.Phase;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolResultBlock;

/**
 * Internal storage machinery: renders {@link Phase} to and from the JSON the string-payload storage
 * kernel persists as the {@code state} document's payload (spec §7). Not API vocabulary — the scope
 * version lives in the kernel's own document version (see the {@code state} recipe), not in this
 * payload.
 *
 * <p>Each phase carries a {@code "type"} discriminator naming it: {@code idle}, {@code
 * awaiting-model}, {@code awaiting-tools} — mirroring the {@code Schemas}/{@code SealedInputs}
 * discriminator convention. Reads are tolerant of unknown fields; a malformed payload or an
 * unrecognized discriminator fails loudly with an {@link IllegalArgumentException} naming the
 * offense. {@code AwaitingTools} round-trips through the canonical constructor, so its
 * pending-non-empty and pending-subset-of-the-turn invariants are re-checked on every read.
 */
public final class StateCodec {

  private static final String TYPE = "type";
  private static final String TYPE_IDLE = "idle";
  private static final String TYPE_AWAITING_MODEL = "awaiting-model";
  private static final String TYPE_AWAITING_TOOLS = "awaiting-tools";

  private StateCodec() {}

  public static String toJson(Phase phase) {
    Objects.requireNonNull(phase, "phase must not be null");
    return writePhase(phase).toString();
  }

  public static Phase phase(String json) {
    Objects.requireNonNull(json, "json must not be null");
    return readPhase(Codecs.readObject(json, "phase"));
  }

  private static ObjectNode writePhase(Phase phase) {
    return switch (phase) {
      case Phase.Idle _ -> Codecs.MAPPER.createObjectNode().put(TYPE, TYPE_IDLE);
      case Phase.AwaitingModel _ -> Codecs.MAPPER.createObjectNode().put(TYPE, TYPE_AWAITING_MODEL);
      case Phase.AwaitingTools(
              Message assistantTurn,
              Set<String> pending,
              List<ToolResultBlock> gathered) ->
          writeAwaitingTools(assistantTurn, pending, gathered);
    };
  }

  private static ObjectNode writeAwaitingTools(
      Message assistantTurn, Set<String> pending, List<ToolResultBlock> gathered) {
    ObjectNode node = Codecs.MAPPER.createObjectNode();
    node.put(TYPE, TYPE_AWAITING_TOOLS);
    node.set("assistantTurn", MessageCodec.writeMessageNode(assistantTurn));
    ArrayNode pendingNode = node.putArray("pending");
    pending.stream().sorted().forEach(pendingNode::add);
    ArrayNode gatheredNode = node.putArray("gathered");
    for (ToolResultBlock block : gathered) {
      gatheredNode.add(MessageCodec.writeBlockNode(block));
    }
    return node;
  }

  private static Phase readPhase(ObjectNode root) {
    String type = Codecs.requireText(root, TYPE, "phase");
    return switch (type) {
      case TYPE_IDLE -> new Phase.Idle();
      case TYPE_AWAITING_MODEL -> new Phase.AwaitingModel();
      case TYPE_AWAITING_TOOLS -> readAwaitingTools(root);
      default ->
          throw new IllegalArgumentException(
              "unknown phase type: "
                  + type
                  + "; expected one of: "
                  + TYPE_IDLE
                  + ", "
                  + TYPE_AWAITING_MODEL
                  + ", "
                  + TYPE_AWAITING_TOOLS);
    };
  }

  private static Phase readAwaitingTools(ObjectNode root) {
    JsonNode turnNode = Codecs.requireField(root, "assistantTurn", "awaiting-tools phase");
    Message assistantTurn =
        MessageCodec.readMessageNode(Codecs.requireObject(turnNode, "assistantTurn"));
    ArrayNode pendingNode = Codecs.requireArray(root, "pending", "awaiting-tools phase");
    Set<String> pending = new LinkedHashSet<>();
    pendingNode.forEach(n -> pending.add(n.asText()));
    ArrayNode gatheredNode = Codecs.requireArray(root, "gathered", "awaiting-tools phase");
    List<ToolResultBlock> gathered = new ArrayList<>();
    for (JsonNode resultNode : gatheredNode) {
      gathered.add(requireToolResultBlock(resultNode));
    }
    return new Phase.AwaitingTools(assistantTurn, pending, gathered);
  }

  private static ToolResultBlock requireToolResultBlock(JsonNode resultNode) {
    ContentBlock block =
        MessageCodec.readBlockNode(Codecs.requireObject(resultNode, "gathered result"));
    if (block instanceof ToolResultBlock toolResult) {
      return toolResult;
    }
    String foundType = resultNode.path(TYPE).asText();
    throw new IllegalArgumentException(
        "gathered must contain only tool-result blocks; found: " + foundType);
  }
}
