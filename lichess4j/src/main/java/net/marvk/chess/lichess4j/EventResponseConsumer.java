package net.marvk.chess.lichess4j;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import lombok.extern.log4j.Log4j2;
import net.marvk.chess.lichess4j.model.*;
import net.marvk.chess.lichess4j.serialization.PerfDeserializer;
import net.marvk.chess.lichess4j.util.HttpUtil;
import org.apache.http.HttpResponse;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.client.methods.AsyncCharConsumer;
import org.apache.http.protocol.HttpContext;

import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.function.Consumer;

@Log4j2
class EventResponseConsumer extends AsyncCharConsumer<Boolean> {
    private static final Gson GSON = new GsonBuilder().registerTypeAdapter(Perf.class, new PerfDeserializer())
                                                      .create();

    private final Consumer<Challenge> challengeConsumer;
    private final Consumer<GameStart> gameStartConsumer;
    private final Consumer<EventResponse> malformedEventConsumer;  // use setter

    EventResponseConsumer(final Consumer<Challenge> challengeConsumer, 
                          final Consumer<GameStart> gameStartConsumer,
                          final Consumer<EventResponse> malformedEventConsumer) {
        this.challengeConsumer = challengeConsumer;
        this.gameStartConsumer = gameStartConsumer;
        this.malformedEventConsumer = malformedEventConsumer;
    }

    @Override
    protected void onCharReceived(final CharBuffer buf, final IOControl ioControl) {
        final String response = HttpUtil.charBufferToString(buf).trim();

        if (response.isBlank()) {
            log.trace("No new events");
            return;
        }

        log.trace("Received event response:\n" + response);

        Arrays.stream(response.split("\n"))
              .map(EventResponseConsumer::safeJson)
              .forEach(this::acceptEvent);
    }

    private void acceptEvent(final EventResponse eventResponse) {
        if (eventResponse == null) {
            log.warn("Received null event response");
            return;
        }

        if (eventResponse.getType() == EventResponse.Type.CHALLENGE) {
            final Challenge challenge = eventResponse.getChallenge();
            log.info("Received challenge: " + challenge);

            challengeConsumer.accept(challenge);
        } else if (eventResponse.getType() == EventResponse.Type.GAME_START) {
            final GameStart game = eventResponse.getGameStart();
            log.info("Received game start event: " + game);

            gameStartConsumer.accept(game);
        } else {
            log.warn("Received malformed event: " + eventResponse);
            malformedEventConsumer.accept(eventResponse);
        }
    }

    @Override
    protected void onResponseReceived(final HttpResponse response) {
    }

    @Override
    protected Boolean buildResult(final HttpContext context) {
        return Boolean.TRUE;
    }

    private static EventResponse safeJson(final String line) {
        try {
            return GSON.fromJson(line, EventResponse.class);
        } catch (final JsonParseException e) {
            log.error("Failed to parse line:\n " + line, e);
            return null;
        }
    }
}
