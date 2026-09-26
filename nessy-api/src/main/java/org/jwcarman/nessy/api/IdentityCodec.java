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

import org.jwcarman.codec.Codec;

/**
 * Bytes, untouched -- the seed handed to every {@link StorageCodecConfigurer}.
 *
 * <p>Public, not because an application ever names it, but because two independent Boot
 * auto-configurations in two different packages both call {@link StorageCodecConfigurer#configure}
 * with it: the JDBC backend's {@code CodecFactory} and the Substrate narration bridge's {@code
 * PayloadTransformer}. Both compare what comes back against {@link #INSTANCE} by reference to tell
 * "nothing was appended" from "a configurer ran and appended something" -- a package-private type
 * would leave one of the two unable to reach it.
 *
 * <p><b>Do not delete the {@link #andThen} override.</b> {@code Codec.andThen} is a {@code default}
 * method, so without this override {@code IdentityCodec.INSTANCE.andThen(transform)} would return a
 * codec that wraps the identity codec around {@code transform} -- correct, but a permanent
 * indirection around every stored byte for an application that never asked for one. Overriding it
 * to hand back {@code transform} directly is what lets a {@link StorageCodecConfigurer} compose
 * from this instance for free, and it is the entire reason this class exists rather than an
 * anonymous {@code Codec<byte[]>}.
 */
public final class IdentityCodec implements Codec<byte[]> {

  public static final IdentityCodec INSTANCE = new IdentityCodec();

  private IdentityCodec() {}

  @Override
  public byte[] encode(byte[] bytes) {
    return bytes;
  }

  @Override
  public byte[] decode(byte[] bytes) {
    return bytes;
  }

  @Override
  public Codec<byte[]> andThen(Codec<byte[]> transform) {
    return transform;
  }
}
