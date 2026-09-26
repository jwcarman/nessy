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
package org.jwcarman.nessy.engine.tool;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ReplyToken;

/**
 * Mints and reads the address a deferring approver or tool hands to whoever will answer.
 *
 * <p>A token carries LOGICAL coordinates -- which agent type, which agent, which call -- encrypted.
 * Never a row id or a thread: an answer may arrive days later, after a restart, after the row it
 * was parked on has been reclaimed by its own watchdog, and only logical coordinates survive that.
 *
 * <p><b>Encrypted rather than looked up.</b> The alternative is a random id resolved against a
 * stored row, which buys early revocation and costs a table to write, index and eventually sweep.
 * Encryption needs none of it -- and the two cases revocation would cover are covered already,
 * because a settled call and an expired deferral both reject a perfectly valid token.
 *
 * <p><b>AES-GCM, so one primitive gives both properties.</b> Opaque, because a holder cannot read
 * the coordinates out of it; unforgeable, because any edit fails authentication. A fresh nonce per
 * token, as GCM requires -- reusing one under the same key is catastrophic rather than merely weak.
 *
 * <p><b>Rotation is a list, not a key.</b> Tokens are minted with the FIRST key and read by trying
 * each in turn, so retiring a key means putting the new one at the front and keeping the old one
 * until every token it minted has expired. Somebody answering on day two of a three-day term is
 * still understood. The cost is operational -- keep the outgoing key for at least the longest
 * deferral -- rather than structural, and dropping it early is the only way to break outstanding
 * tokens.
 *
 * <p>It is still a BEARER token: whoever holds it can answer that one call. Encryption stops a
 * holder forging a token for a call it was never given; it does not stop misuse of one it was.
 */
public final class ReplyTokens {

  private static final String ALGORITHM = "AES/GCM/NoPadding";
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;

  /**
   * ASCII unit separator, which is exactly what it is for.
   *
   * <p>Not a comma or a colon: both occur in things people name, and a separator that can appear
   * inside a field is a separator that can be used to move a boundary.
   */
  private static final char SEPARATOR = '\u001F';

  /**
   * What a token names.
   *
   * <p>{@code requestSeq} is part of the address rather than decoration: a model's call ids are
   * unique within one response only, so two turns can each produce a {@code "call_1"}. The seq of
   * the entry that asked for the work is what tells them apart, and it is also how the parked
   * effect is found again.
   */
  public record Coordinates(String agentType, UUID agentId, Seq requestSeq, CallId callId) {}

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
    for (int i = 0; i < keys.size(); i++) {
      requireUsable(keys.get(i), i);
    }
    this.keys = List.copyOf(keys);
  }

  public ReplyTokens(SecretKey key) {
    this(List.of(Objects.requireNonNull(key, "key must not be null")));
  }

  /**
   * Rejects a key AES cannot use, here rather than at the first mint.
   *
   * <p>A cipher only sees its key when something asks it to encrypt, so a mistyped or truncated one
   * would otherwise surface the first time a call parked on a person -- the worst moment to find a
   * configuration error, and the one furthest from the line that caused it. Checking at
   * construction means a bad key fails at startup, next to the property that set it.
   *
   * <p>Length is what can be checked. Whether the bytes are the RIGHT key is not knowable here: a
   * wrong key of the right length simply reads no token, which is what the "not issued by this
   * engine" message is for.
   */
  private static void requireUsable(SecretKey key, int position) {
    Objects.requireNonNull(key, "key must not be null");
    byte[] material = key.getEncoded();
    if (material == null) {
      // A hardware or KMS-backed key does not expose its bytes, and does not need us to
      // check them.
      return;
    }
    int length = material.length;
    if (length != 16 && length != 24 && length != 32) {
      throw new IllegalArgumentException(
          "reply key %d is %d bytes; AES needs 16, 24 or 32 (use 32)".formatted(position, length));
    }
  }

  /** From raw key material -- 16, 24 or 32 bytes, newest first. */
  public static ReplyTokens withKeys(byte[]... keys) {
    List<SecretKey> secrets = new ArrayList<>(keys.length);
    for (byte[] key : keys) {
      secrets.add(new SecretKeySpec(key, "AES"));
    }
    return new ReplyTokens(secrets);
  }

  public static ReplyTokens withKey(byte[] key) {
    return withKeys(key);
  }

  /**
   * A key that lasts as long as this process.
   *
   * <p>For tests and for a single-process demo, and useless in production: every restart
   * invalidates every outstanding token, so anything that deferred before a deploy can never be
   * answered.
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

  public ReplyToken mint(AgentType agentType, AgentId agentId, Seq requestSeq, CallId callId) {
    byte[] plain = encode(new Coordinates(agentType.value(), agentId.value(), requestSeq, callId));
    byte[] nonce = new byte[NONCE_BYTES];
    random.nextBytes(nonce);
    byte[] sealed = crypt(keys.getFirst(), Cipher.ENCRYPT_MODE, nonce, plain);
    return new ReplyToken(
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                ByteBuffer.allocate(nonce.length + sealed.length).put(nonce).put(sealed).array()));
  }

  /**
   * @throws IllegalArgumentException if the token was not issued under any of these keys, or has
   *     been edited. Authentic is not the same as open: a token that reads cleanly says only that
   *     we issued it, never that the call is still waiting.
   */
  public Coordinates read(ReplyToken token) {
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
      } catch (IllegalArgumentException _) {
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

  /**
   * Written by hand rather than through a mapper.
   *
   * <p>Four flat fields, and the alternative is a JSON library reachable from the one class whose
   * output has to read back identically in three days, under whatever is deployed by then.
   *
   * <p>The separator check is not a parsing nicety. A field that could contain the separator could
   * move a boundary, and the result of that reads back as valid, authentic coordinates for a
   * different call -- a forgery built out of a token somebody was legitimately given, rather than a
   * failure anyone would see.
   */
  private static byte[] encode(Coordinates coordinates) {
    if (coordinates.agentType().indexOf(SEPARATOR) >= 0
        || coordinates.callId().value().indexOf(SEPARATOR) >= 0) {
      throw new IllegalArgumentException("coordinates must not contain a unit separator");
    }
    return String.join(
            String.valueOf(SEPARATOR),
            coordinates.agentType(),
            coordinates.agentId().toString(),
            Long.toString(coordinates.requestSeq().value()),
            coordinates.callId().value())
        .getBytes(StandardCharsets.UTF_8);
  }

  private static Coordinates decode(byte[] plain) {
    String[] parts = new String(plain, StandardCharsets.UTF_8).split(String.valueOf(SEPARATOR), -1);
    if (parts.length != 4) {
      throw new IllegalArgumentException("not a reply token");
    }
    try {
      return new Coordinates(
          parts[0],
          UUID.fromString(parts[1]),
          new Seq(Long.parseLong(parts[2])),
          new CallId(parts[3]));
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("not a reply token", e);
    }
  }
}
