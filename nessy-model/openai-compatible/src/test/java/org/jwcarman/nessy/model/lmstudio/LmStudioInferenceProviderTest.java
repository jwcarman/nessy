package org.jwcarman.nessy.model.lmstudio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.spi.inference.Failure;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.jwcarman.nessy.engine.history.HistoryEntry;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.api.turn.Observation;
import org.jwcarman.nessy.api.SystemPrompt;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Seq;

/**
 * What a failed call <em>means</em> is decided here and nowhere else, because this is the only
 * class that knows what a status code says on this provider's wire.
 */
class LmStudioInferenceProviderTest {

    private static final String URL = "http://localhost:1234/v1/chat/completions";

    private MockRestServiceServer server;
    private InferenceProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:1234");
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new LmStudioInferenceProvider(builder.build());
    }

    @AfterEach
    void verifyServer() {
        server.verify();
    }

    private InferenceResult infer() {
        return infer(InferenceOptions.of("a-model"));
    }

    private InferenceResult infer(InferenceOptions options) {
        Turn openTurn = new Turn(new TurnId(1),
                new Observation(new Seq(1), HistoryEntry.ObservationReceived.text("what lake?")),
                List.of(), null, 0);
        return provider.infer(
                new InferenceRequest(new SystemPrompt("You are a test assistant."),
                        InferenceContext.of(List.of(openTurn)), List.of(), options));
    }

    /** Every classified failure is an arm now, so a test that wants one says so out loud. */
    private Failure failureFrom() {
        InferenceResult result = infer();
        assertThat(result).isInstanceOf(InferenceResult.Fault.class);
        return ((InferenceResult.Fault) result).failure();
    }

    private void respondWith(HttpStatus status) {
        server.expect(requestTo(URL)).andRespond(withStatus(status));
    }

    private void respondWith(String body) {
        server.expect(requestTo(URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    void anAnswerComesBackAsText() {
        respondWith("""
                {"choices":[{"message":{"role":"assistant","content":"a lake"}}]}""");

        assertThat(infer()).isEqualTo(new InferenceResult.Answer(HistoryEntry.InferenceAnswered.text("a lake")));
    }

    /**
     * The model name travels in the options, not in the adapter. If it stopped doing so, one
     * connection could no longer serve two agent types asking for different models -- and nothing
     * else in the system would notice until both got the same answer.
     */
    @Test
    void theModelCalledIsTheOneTheOptionsName() {
        server.expect(requestTo(URL))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"gpt-oss\"")))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"ok"}}]}""",
                        MediaType.APPLICATION_JSON));

        assertThat(infer(InferenceOptions.of("gpt-oss")))
                .isInstanceOf(InferenceResult.Answer.class);
    }

    /**
     * Zero is a real value to this API and asks for an empty answer, so "no ceiling of ours" has
     * to be said by leaving the field out rather than by sending the number.
     */
    @Test
    void noCeilingMeansTheFieldIsNotSentAtAll() {
        server.expect(requestTo(URL))
                .andExpect(content().string(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("max"))))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"ok"}}]}""",
                        MediaType.APPLICATION_JSON));

        assertThat(infer()).isInstanceOf(InferenceResult.Answer.class);
    }

    @Test
    void aCeilingIsSentWhenOneIsAskedFor() {
        server.expect(requestTo(URL))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("256")))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"ok"}}]}""",
                        MediaType.APPLICATION_JSON));

        assertThat(infer(new InferenceOptions("a-model", 256)))
                .isInstanceOf(InferenceResult.Answer.class);
    }

    @Test
    void aMalformedRequestIsPermanent() {
        respondWith(HttpStatus.BAD_REQUEST);

        assertThat(failureFrom())
                .as("no budget can make a rejected request succeed")
                .isInstanceOf(Failure.Permanent.class);
    }

    @Test
    void anUnknownModelIsPermanent() {
        respondWith(HttpStatus.NOT_FOUND);

        assertThat(failureFrom()).isInstanceOf(Failure.Permanent.class);
    }

    @Test
    void beingRateLimitedIsTransient() {
        respondWith(HttpStatus.TOO_MANY_REQUESTS);

        assertThat(failureFrom())
                .as("429 is the server asking for later, not refusing")
                .isInstanceOf(Failure.Transient.class);
    }

    @Test
    void aServerErrorIsTransient() {
        respondWith(HttpStatus.SERVICE_UNAVAILABLE);

        assertThat(failureFrom()).isInstanceOf(Failure.Transient.class);
    }

    /**
     * A 200 carrying no answer. Retrying returns the same nothing, so it is not transient even
     * though the transport called it a success.
     */
    @Test
    void anEmptyChoicesArrayIsPermanent() {
        respondWith("{\"choices\":[]}");

        assertThat(failureFrom()).isInstanceOf(Failure.Permanent.class);
    }

    @Test
    void aFailureCarriesItsReasonForTheFactItBecomes() {
        respondWith(HttpStatus.BAD_REQUEST);

        assertThat(failureFrom().reason()).contains("400");
    }
}
