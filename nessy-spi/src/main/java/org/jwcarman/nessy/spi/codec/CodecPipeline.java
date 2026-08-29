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
package org.jwcarman.nessy.spi.codec;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.codec.spi.CodecFactory;
import org.jwcarman.codec.spi.TypeRef;

/**
 * The {@code byte[] -> byte[]} transforms every stored payload passes through, in declaration
 * order, applied identically by the actor serializer and by {@code Substrate}.
 *
 * <p>One pipeline for both stores is the whole point. A chain that reached only one of them would
 * give you encrypted actor state beside plaintext memory — worse than choosing either consistently,
 * and encryption is the first transform anyone reaches for.
 *
 * <h2>Why the chain is written into the bytes</h2>
 *
 * <p>The two consumers have different metadata slots — Pekko has a serializer {@code manifest},
 * {@code Substrate} has a {@code kind} column — and <b>nothing is common to both</b>. Recording the
 * chain in each would mean two mechanisms that drift. So each payload carries its own short header
 * naming the transforms that produced it, and decoding reads that rather than assuming today's
 * configuration produced yesterday's bytes.
 *
 * <p>Without it, two ordinary config edits corrupt data silently:
 *
 * <ul>
 *   <li><b>Appending</b> a transform orphans every row already written, because decode would apply
 *       an inverse that was never applied forwards.
 *   <li><b>Reordering</b> the declaration is worse — {@code gzip} then {@code base64} decoded as
 *       {@code base64} then {@code gzip} produces garbage rather than an error.
 * </ul>
 *
 * <p>Bytes with no header are returned untouched, so payloads written before a pipeline existed
 * still read.
 *
 * <h2>Composing it</h2>
 *
 * <p>This IS a {@link Codec Codec&lt;byte[]&gt;}, so anywhere a codec is built — and there are many
 * such places — it composes directly:
 *
 * <pre>{@code
 * Codec<Thing> stored = codecs.create(Thing.class).andThen(pipeline);
 * }</pre>
 *
 * <p>But composing by hand at every site is the failure waiting to happen — one forgotten {@code
 * andThen} writes a store's payloads in the clear, and nothing fails until someone reads the table.
 * So the pipeline is baked into a {@link CodecFactory} instead: ask {@link #factoryOver} once, hand
 * the result around, and every codec anyone creates already carries it.
 *
 * <p>Compose it ONCE per payload. A codec that came from {@link #factoryOver} must not also be
 * passed through {@code andThen} — that transforms twice going down and only once coming up.
 */
public final class CodecPipeline implements Codec<byte[]> {

  /** Two bytes nothing else here starts with, so a headerless payload is recognisable. */
  private static final byte[] MAGIC = {(byte) 0x4E, (byte) 0x59};

  private static final int MAX_NAME_LENGTH = 255;

  /** Every transform this pipeline can apply, by name — including ones no longer active. */
  private final Map<String, Codec<byte[]>> known;

  /** The transforms applied to new payloads, in declaration order. */
  private final List<String> active;

  private CodecPipeline(Map<String, Codec<byte[]>> known, List<String> active) {
    this.known = Map.copyOf(known);
    this.active = List.copyOf(active);
  }

  /** A pipeline that transforms nothing — payloads are stored as they arrive. */
  public static CodecPipeline none() {
    return new CodecPipeline(Map.of(), List.of());
  }

  /** The pipeline the given customizers describe, applied in the order they are declared. */
  public static CodecPipeline of(List<CodecCustomizer> customizers) {
    Objects.requireNonNull(customizers, "customizers must not be null");
    Chain chain = new Chain();
    customizers.forEach(customizer -> customizer.customize(chain));
    return new CodecPipeline(chain.byName, chain.order);
  }

  /**
   * {@code base}, except every codec it creates already runs through this pipeline.
   *
   * <p>The door for "many places need to create a codec": they all take this factory, and none of
   * them has to remember the pipeline exists. A codec is a function from a type to bytes; this
   * makes it a function from a type to STORED bytes.
   */
  public CodecFactory factoryOver(CodecFactory base) {
    Objects.requireNonNull(base, "base must not be null");
    CodecPipeline self = this;
    return new CodecFactory() {
      @Override
      public <T> Codec<T> create(TypeRef<T> type) {
        return base.create(type).andThen(self);
      }
    };
  }

  /** The transforms applied to new payloads, in the order they run. */
  public List<String> transforms() {
    return active;
  }

  /** Applies every active transform in order and stamps the header naming them. */
  @Override
  public byte[] encode(byte[] payload) {
    Objects.requireNonNull(payload, "payload must not be null");
    byte[] transformed = payload;
    for (String name : active) {
      transformed = known.get(name).encode(transformed);
    }
    return withHeader(transformed);
  }

  /**
   * Reverses whatever transforms the payload's own header names — not whatever is configured today.
   *
   * @throws IllegalStateException if the payload names a transform this pipeline does not know,
   *     which means a transform was removed from the configuration while data written with it
   *     survives
   */
  @Override
  public byte[] decode(byte[] stored) {
    Objects.requireNonNull(stored, "stored must not be null");
    if (!hasHeader(stored)) {
      return stored;
    }
    int cursor = MAGIC.length;
    int count = Byte.toUnsignedInt(stored[cursor++]);
    List<String> applied = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      int length = Byte.toUnsignedInt(stored[cursor++]);
      applied.add(new String(stored, cursor, length, StandardCharsets.UTF_8));
      cursor += length;
    }
    byte[] payload = new byte[stored.length - cursor];
    System.arraycopy(stored, cursor, payload, 0, payload.length);

    for (int i = applied.size() - 1; i >= 0; i--) {
      String name = applied.get(i);
      Codec<byte[]> transform = known.get(name);
      if (transform == null) {
        throw new IllegalStateException(
            "payload was written with transform '%s', which this pipeline no longer knows; known: %s"
                .formatted(name, known.keySet()));
      }
      payload = transform.decode(payload);
    }
    return payload;
  }

  private boolean hasHeader(byte[] stored) {
    return stored.length >= MAGIC.length + 1 && stored[0] == MAGIC[0] && stored[1] == MAGIC[1];
  }

  private byte[] withHeader(byte[] payload) {
    var out = new ByteArrayOutputStream(payload.length + 16);
    out.writeBytes(MAGIC);
    out.write(active.size());
    for (String name : active) {
      byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
      out.write(bytes.length);
      out.writeBytes(bytes);
    }
    out.writeBytes(payload);
    return out.toByteArray();
  }

  /** What a {@link CodecCustomizer} appends to. Order of appending is order of application. */
  public static final class Chain {

    private final Map<String, Codec<byte[]>> byName = new LinkedHashMap<>();
    private final List<String> order = new ArrayList<>();

    private Chain() {}

    /**
     * Appends a transform, applied after everything already appended.
     *
     * <p>The name is written into every payload this chain produces, so it is durable data:
     * renaming a transform makes existing payloads unreadable. Choose a boring, permanent one.
     */
    public Chain append(String name, Codec<byte[]> transform) {
      Objects.requireNonNull(name, "name must not be null");
      Objects.requireNonNull(transform, "transform must not be null");
      if (name.isBlank()) {
        throw new IllegalArgumentException("transform name must not be blank");
      }
      byte[] encoded = name.getBytes(StandardCharsets.UTF_8);
      if (encoded.length > MAX_NAME_LENGTH) {
        throw new IllegalArgumentException(
            "transform name must encode to at most %d bytes: %s".formatted(MAX_NAME_LENGTH, name));
      }
      if (byName.putIfAbsent(name, transform) != null) {
        throw new IllegalArgumentException(
            "transform '%s' is already in the chain".formatted(name));
      }
      order.add(name);
      return this;
    }
  }
}
