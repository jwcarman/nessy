package org.jwcarman.nessy.engine.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ReplyToken;

/**
 * A bearer credential, so what matters is not that it round-trips but what it refuses.
 *
 * <p>Every test below is one way an attacker or an accident could turn a token they were given into
 * authority over a call they were not. The happy path is one test; the rest are the reason this is
 * encrypted rather than a formatted string.
 */
class ReplyTokensTest {

  private static final AgentType TYPE = new AgentType("chat");
  private static final CallId CALL = new CallId("call_1");
  private static final AgentId AGENT = new AgentId(UUID.randomUUID());

  /** The unit separator the encoding uses, which no coordinate may contain. */
  private static final String SEP = "\u001F";

  private static final byte[] KEY_A = key((byte) 1);
  private static final byte[] KEY_B = key((byte) 2);

  private static byte[] key(byte fill) {
    byte[] key = new byte[32];
    java.util.Arrays.fill(key, fill);
    return key;
  }

  private final ReplyTokens tokens = ReplyTokens.withKey(KEY_A);

  @Test
  void aTokenReadsBackAsTheCallItNames() {
    ReplyToken token = tokens.mint(TYPE, AGENT, new Seq(42), CALL);

    assertThat(tokens.read(token))
        .isEqualTo(new ReplyTokens.Coordinates("chat", AGENT.value(), new Seq(42), CALL));
  }

  /**
   * A holder must not be able to see, let alone edit, which call they were given. Checked on the
   * decoded bytes, with values long enough not to turn up in a random nonce by chance -- a
   * two-digit sequence number does, about once in fifty runs.
   */
  @Test
  void aTokenShowsTheHolderNothing() {
    Seq seq = new Seq(4_242_424_242L);
    ReplyToken token = tokens.mint(TYPE, AGENT, seq, CALL);

    String raw =
        new String(Base64.getUrlDecoder().decode(token.value()), StandardCharsets.ISO_8859_1);
    assertThat(raw)
        .doesNotContain("chat")
        .doesNotContain("call_1")
        .doesNotContain(AGENT.value().toString())
        .doesNotContain(Long.toString(seq.value()));
  }

  /**
   * GCM's authentication doing its job. Without it, a holder given authority over one call could
   * flip a byte and hold authority over another.
   */
  @Test
  void anEditedTokenIsRefusedRatherThanRead() {
    byte[] raw = Base64.getUrlDecoder().decode(tokens.mint(TYPE, AGENT, new Seq(42), CALL).value());
    raw[raw.length - 1] ^= 0x01;
    ReplyToken tampered =
        new ReplyToken(Base64.getUrlEncoder().withoutPadding().encodeToString(raw));

    assertThatThrownBy(() -> tokens.read(tampered)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aTokenFromAnotherEngineIsRefused() {
    ReplyToken theirs = ReplyTokens.withKey(KEY_B).mint(TYPE, AGENT, new Seq(42), CALL);

    assertThatThrownBy(() -> tokens.read(theirs))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not a reply token issued by this engine");
  }

  @Test
  void somethingThatIsNotATokenAtAllIsRefused() {
    ReplyToken notBase64 = new ReplyToken("not base64 at all!!");
    ReplyToken tooShort =
        new ReplyToken(
            Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("short".getBytes(StandardCharsets.UTF_8)));
    assertThatThrownBy(() -> tokens.read(notBase64)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tokens.read(tooShort)).isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * GCM requires a fresh nonce per token under one key, and reusing one is catastrophic rather than
   * merely weak. Identical coordinates minting identical bytes would be the visible symptom of that
   * mistake.
   */
  @Test
  void twoTokensForTheSameCallAreNeverTheSameBytes() {
    assertThat(tokens.mint(TYPE, AGENT, new Seq(42), CALL).value())
        .isNotEqualTo(tokens.mint(TYPE, AGENT, new Seq(42), CALL).value());
  }

  // ---- rotation --------------------------------------------------------------------------

  /**
   * The point of a list. Somebody answering on day two of a three-day term was handed a token
   * minted under the key that has since been retired, and must still be understood.
   */
  @Test
  void aTokenMintedUnderARetiredKeyIsStillRead() {
    ReplyToken old = ReplyTokens.withKey(KEY_A).mint(TYPE, AGENT, new Seq(42), CALL);
    ReplyTokens rotated = ReplyTokens.withKeys(KEY_B, KEY_A);

    assertThat(rotated.read(old).callId()).isEqualTo(CALL);
  }

  @Test
  void afterRotationNewTokensAreMintedUnderTheNewKey() {
    ReplyTokens rotated = ReplyTokens.withKeys(KEY_B, KEY_A);
    ReplyToken fresh = rotated.mint(TYPE, AGENT, new Seq(42), CALL);

    assertThat(ReplyTokens.withKey(KEY_B).read(fresh).callId()).isEqualTo(CALL);
    ReplyTokens onlyTheOldKey = ReplyTokens.withKey(KEY_A);
    assertThatThrownBy(() -> onlyTheOldKey.read(fresh))
        .as("dropping the outgoing key early is the only way to break outstanding tokens")
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---- configuration ---------------------------------------------------------------------

  /**
   * At startup, next to the property that set it -- not the first time a call parks on a person,
   * which is the worst moment to find a configuration error and the furthest from its cause.
   */
  @Test
  void aKeyOfTheWrongLengthFailsAtConstruction() {
    assertThatThrownBy(() -> ReplyTokens.withKey(new byte[7]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("AES needs 16, 24 or 32");
  }

  @Test
  void mintingWithNoKeysIsRefused() {
    assertThatThrownBy(() -> new ReplyTokens(java.util.List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * The separator cannot appear inside a field, because a field that could contain it could move a
   * boundary -- and the result reads back as valid, authentic coordinates for a different call.
   * That is a forgery built from a token somebody was legitimately given, not a failure anybody
   * would see.
   */
  @Test
  void coordinatesThatCouldMoveABoundaryAreRefused() {
    AgentType sneaky = new AgentType("chat" + SEP + AGENT.value() + SEP + "42");

    Seq seq = new Seq(42);
    assertThatThrownBy(() -> tokens.mint(sneaky, AGENT, seq, CALL))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unit separator");
  }

  /** Distinct calls must never collide, and a call id is a provider's string, not ours. */
  @Test
  void callsAreToldApartByTurnAsWellAsByCallId() {
    ReplyTokens.Coordinates first = tokens.read(tokens.mint(TYPE, AGENT, new Seq(2), CALL));
    ReplyTokens.Coordinates second = tokens.read(tokens.mint(TYPE, AGENT, new Seq(9), CALL));

    assertThat(first).isNotEqualTo(second);
    assertThat(first.requestSeq()).isEqualTo(new Seq(2));
    assertThat(second.requestSeq()).isEqualTo(new Seq(9));
  }

  /** Useless in production, and the javadoc says so -- but it must at least work. */
  @Test
  void anEphemeralEngineCanReadItsOwnTokens() {
    ReplyTokens ephemeral = ReplyTokens.ephemeral();

    assertThat(ephemeral.read(ephemeral.mint(TYPE, AGENT, new Seq(1), CALL)).callId())
        .isEqualTo(CALL);
  }
}
