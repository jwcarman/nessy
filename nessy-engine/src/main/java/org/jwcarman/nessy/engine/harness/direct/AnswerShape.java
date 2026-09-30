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
package org.jwcarman.nessy.engine.harness.direct;

import java.lang.reflect.Type;
import java.util.Objects;
import org.jwcarman.nessy.api.JsonSchema;
import org.jwcarman.nessy.api.JsonSchemaGenerator;
import org.jwcarman.nessy.api.OutputReader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The schema an answer is asked for with, and how the reply is read back.
 *
 * <p>Providers require an object at the root of an answer schema, so a type that generates anything
 * else -- an array, a string, an enum, a union -- goes as {@code {"type":"object",
 * "properties":{"value":<the schema>},"required":["value"],"additionalProperties":false}}, with the
 * schema's {@code $defs} hoisted to the wrapper's root so its {@code $ref}s still resolve. An
 * object-rooted answer goes as generated and is read as it comes.
 *
 * <p>Only the value handed to the caller is unwrapped. The model's own text, wrapper and all, is
 * what the transcript keeps.
 */
final class AnswerShape {

  private static final String VALUE = "value";
  private static final String DEFS = "$defs";
  private static final String SCHEMA = "$schema";

  private final JsonSchema toldToProvider;
  private final boolean wrapped;
  private final ObjectMapper mapper;

  private AnswerShape(JsonSchema toldToProvider, boolean wrapped, ObjectMapper mapper) {
    this.toldToProvider = toldToProvider;
    this.wrapped = wrapped;
    this.mapper = mapper;
  }

  static AnswerShape of(Type answers, JsonSchemaGenerator schemas, ObjectMapper mapper) {
    Objects.requireNonNull(answers, "answers must not be null");
    Objects.requireNonNull(schemas, "schemas must not be null");
    Objects.requireNonNull(mapper, "mapper must not be null");
    JsonSchema generated = schemas.generate(answers);
    JsonNode root = mapper.readTree(generated.json());
    if (root instanceof ObjectNode object && "object".equals(object.path("type").asString())) {
      return new AnswerShape(generated, false, mapper);
    }
    if (!(root instanceof ObjectNode inner)) {
      // A boolean schema has no keywords to hoist; it wraps as it is.
      return new AnswerShape(
          new JsonSchema(wrap(mapper, root, null, null).toString()), true, mapper);
    }
    JsonNode defs = inner.remove(DEFS);
    JsonNode dialect = inner.remove(SCHEMA);
    return new AnswerShape(
        new JsonSchema(wrap(mapper, inner, defs, dialect).toString()), true, mapper);
  }

  private static ObjectNode wrap(
      ObjectMapper mapper, JsonNode inner, JsonNode defs, JsonNode dialect) {
    ObjectNode wrapper = mapper.createObjectNode();
    if (dialect != null) {
      wrapper.set(SCHEMA, dialect);
    }
    wrapper.put("type", "object");
    wrapper.putObject("properties").set(VALUE, inner);
    wrapper.putArray("required").add(VALUE);
    wrapper.put("additionalProperties", false);
    if (defs != null) {
      wrapper.set(DEFS, defs);
    }
    return wrapper;
  }

  /** What the provider is told. */
  JsonSchema schema() {
    return toldToProvider;
  }

  /**
   * The reader a harness uses for this shape.
   *
   * <p>A wrapped answer is unwrapped once, here, so the reader it is given sees exactly what it
   * would have for an answer that was never wrapped.
   *
   * @throws IllegalArgumentException from the returned reader, when a wrapped answer has no {@code
   *     value}
   */
  <O> OutputReader<O> reading(OutputReader<O> reader) {
    Objects.requireNonNull(reader, "reader must not be null");
    if (!wrapped) {
      return reader;
    }
    return text -> {
      JsonNode answer = mapper.readTree(text);
      JsonNode value = answer.get(VALUE);
      if (value == null) {
        throw new IllegalArgumentException("the answer has no \"value\" to unwrap: " + text);
      }
      return reader.read(value.toString());
    };
  }
}
