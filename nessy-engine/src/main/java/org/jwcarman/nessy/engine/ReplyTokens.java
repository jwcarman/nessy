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
package org.jwcarman.nessy.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.tool.ReplyToken;

/**
 * Mints and reads the token a deferring tool hands to whoever will answer.
 *
 * <p>A token carries LOGICAL coordinates — which agent type, which agent, which call — encrypted.
 * Never an actor path: an answer may arrive days later, after a restart or a rebalancing, and only
 * logical coordinates survive that.
 *
 * <p><b>Encrypted rather than looked up.</b> The alternative was a random id resolved against a
 * stored row, which buys early revocation but needs a kind of its own: claims are scoped {@code
 * claim/agentId/turnId}, and building that key needs the agent id, which is the very thing the
 * lookup would be for. Encryption needs no row, no second kind, and no cleanup — and the two cases
 * revocation would cover are already covered, since a settled call and an expired deferral both
 * reject a perfectly valid token.
 *
 * <p><b>AES-GCM, so one primitive gives both properties.</b> Opaque, because a holder cannot read
 * the coordinates; unforgeable, because any edit fails authentication. A fresh nonce per token, as
 * GCM requires — reusing one with the same key is catastrophic rather than merely weak.
 *
 * <p><b>Rotation is a list, not a key.</b> Tokens are minted with the FIRST key and read by trying
 * each in turn, so retiring a key means putting the new one at the front and keeping the old one
 * until every token it minted has expired. A vendor answering on day two of a three-day term is
 * still understood. The cost is operational — retain the outgoing key for at least the longest
 * deferral term — rather than structural, and dropping it early is the only way to break
 * outstanding tokens.
 *
 * <p>It is still a BEARER token: whoever holds it can answer that one call. Encryption stops a
 * holder forging a token for a call it was never given; it does not stop misuse of one it was.
 */
public final class ReplyTokens {

  private static final String ALGORITHM = "AES/GCM/NoPadding";
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;

  /** What a token names. */
  record Coordinates(String agentType, String agentId, String callId) {}

  private final List<SecretKey> keys;
  private final SecureRandom random = new SecureRandom();

  /**
   * @param keys newest first. The first mints; every one is tried on read, so a key stays useful
   *     for as long as it is listed.
   */
  public ReplyTokens(List<SecretKey> keys) {
    Objects.requireNonNull(keys, "keys must not be null");
    if (keys.isEmpty()) {
      throw new IllegalArgumentException("at least one key is needed to mint a token");
    }
    this.keys = List.copyOf(keys);
  }

  public ReplyTokens(SecretKey key) {
    this(List.of(Objects.requireNonNull(key, "key must not be null")));
  }

  /** From raw key material — 16, 24, or 32 bytes, newest first. */
  public static ReplyTokens withKeys(byte[]... keys) {
    List<SecretKey> secrets = new ArrayList<>(keys.length);
    for (byte[] key : keys) {
      secrets.add(new SecretKeySpec(key, "AES"));
    }
    return new ReplyTokens(secrets);
  }

  /** From raw key material — 16, 24, or 32 bytes. */
  public static ReplyTokens withKey(byte[] key) {
    return withKeys(key);
  }

  /**
   * A key that lasts as long as this process.
   *
   * <p>For tests and for a single-process demo. Useless in production: every restart invalidates
   * every outstanding token, so a tool that deferred before a deploy can never be answered.
   */
  public static ReplyTokens ephemeral() {
    try {
      KeyGenerator keys = KeyGenerator.getInstance("AES");
      keys.init(256);
      return new ReplyTokens(keys.generateKey());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("AES is not available", e);
    }
  }

  ReplyToken mint(AgentType agentType, AgentId agentId, String callId) {
    byte[] plain = encode(new Coordinates(agentType.name(), agentId.value(), callId));
    byte[] nonce = new byte[NONCE_BYTES];
    random.nextBytes(nonce);
    byte[] sealed = crypt(keys.getFirst(), Cipher.ENCRYPT_MODE, nonce, plain);
    return ReplyToken.of(
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                ByteBuffer.allocate(nonce.length + sealed.length).put(nonce).put(sealed).array()));
  }

  /**
   * @throws IllegalArgumentException if the token was not issued by this key, or has been edited.
   *     Authentic is not the same as open: a token that reads cleanly says only that we issued it,
   *     never that the call is still waiting.
   */
  Coordinates read(ReplyToken token) {
    byte[] raw;
    try {
      raw = Base64.getUrlDecoder().decode(token.value());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("not a reply token", e);
    }
    if (raw.length <= NONCE_BYTES) {
      throw new IllegalArgumentException("not a reply token");
    }
    byte[] nonce = new byte[NONCE_BYTES];
    byte[] sealed = new byte[raw.length - NONCE_BYTES];
    System.arraycopy(raw, 0, nonce, 0, NONCE_BYTES);
    System.arraycopy(raw, NONCE_BYTES, sealed, 0, sealed.length);
    return decode(open(nonce, sealed));
  }

  /**
   * Tries every key, newest first.
   *
   * <p>Authentication is what makes this safe rather than a guess: a wrong key cannot produce
   * plausible-looking coordinates, it fails outright. So "try them all" costs a few microseconds
   * during a rotation window and nothing the rest of the time.
   */
  private byte[] open(byte[] nonce, byte[] sealed) {
    for (SecretKey candidate : keys) {
      try {
        return crypt(candidate, Cipher.DECRYPT_MODE, nonce, sealed);
      } catch (IllegalArgumentException tryTheNextOne) {
        // Minted under an older key, or not ours at all. The loop decides which.
      }
    }
    throw new IllegalArgumentException("not a reply token issued by this engine");
  }

  private static byte[] crypt(SecretKey key, int mode, byte[] nonce, byte[] input) {
    try {
      Cipher cipher = Cipher.getInstance(ALGORITHM);
      cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
      return cipher.doFinal(input);
    } catch (GeneralSecurityException e) {
      throw new IllegalArgumentException("not a reply token issued by this engine", e);
    }
  }

  private static byte[] encode(Coordinates coordinates) {
    try {
      return EngineMapper.INSTANCE.writeValueAsBytes(coordinates);
    } catch (IOException e) {
      throw new UncheckedIOException("could not mint a reply token", e);
    }
  }

  private static Coordinates decode(byte[] json) {
    try {
      return EngineMapper.INSTANCE.readValue(
          new String(json, StandardCharsets.UTF_8), Coordinates.class);
    } catch (IOException e) {
      throw new IllegalArgumentException("not a reply token", e);
    }
  }
}
