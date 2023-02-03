package net.marvk.chess.lichess4j;

import lombok.extern.log4j.Log4j2;
import net.marvk.chess.core.Color;
import net.marvk.chess.core.Fen;
import net.marvk.chess.core.UciMove;
import net.marvk.chess.core.bitboards.Bitboard;
import net.marvk.chess.lichess4j.model.ChatLine;
import net.marvk.chess.lichess4j.model.GameState;
import net.marvk.chess.lichess4j.model.GameStateFull;
import net.marvk.chess.lichess4j.util.HttpUtil;
import net.marvk.chess.uci4j.EngineFactory;
import net.marvk.chess.uci4j.Go;
import net.marvk.chess.uci4j.UciEngine;
import net.marvk.chess.uci4j.UiChannel;
import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Consumer;

@Log4j2
class GameThread implements Runnable, UiChannel {

    private final String gameId;
    private final String apiToken;
    private final CloseableHttpClient httpClient;
    private final ExecutorService executorService;
    private final String botId;
    private final UciEngine engine;
    private final ChatMessageEventHandler chatMessageEventHandler;

    private Color myColor;
    private String initialFen;

    private GameStateFull initialGameState;
    private GameState lastGameState;

    /**
     * Executed at the start of {@link GameThread#acceptFullGameState(GameStateFull)}<br>
     * Use {@link #setPreAcceptFullGameStateHook(Consumer)}
     */
    private Consumer<GameStateFull> preAcceptFullGameStateHook = o -> {};

    /**
     * Executed at the start of {@link GameThread#acceptGameState(GameState)}<br>
     * Use {@link #setPreAcceptGameStateHook(Consumer)}
     */
    private Consumer<GameState> preAcceptGameStateHook = o -> {};

    /**
     * Executed at the start of {@link GameThread#acceptChatLine(ChatLine)}<br>
     * Use {@link #setPreAcceptChatLine(Consumer)}
     */
    private Consumer<ChatLine> preAcceptChatLine = o -> {};

    GameThread(final String botId,
               final String apiToken,
               final String gameId,
               final CloseableHttpClient httpClient,
               final ExecutorService executorService,
               final EngineFactory engine,
               final ChatMessageEventHandler chatMessageEventHandler) {
        this.gameId = gameId;
        this.apiToken = apiToken;
        this.httpClient = httpClient;
        this.executorService = executorService;
        this.botId = botId;
        this.engine = engine.create(this);
        this.chatMessageEventHandler = chatMessageEventHandler;
    }

    private void acceptFullGameState(final GameStateFull gameStateFull) {
        if (initialGameState != null) {
            log.warn("Received initial game state twice");
        } else {
            initialGameState = gameStateFull;
        }
        if (preAcceptFullGameStateHook != null) {
            preAcceptFullGameStateHook.accept(gameStateFull);
        }

        if (botId.equals(gameStateFull.getWhite().getId())) {
            this.myColor = Color.WHITE;
        } else if (botId.equals(gameStateFull.getBlack().getId())) {
            this.myColor = Color.BLACK;
        }

        initialFen = gameStateFull.getInitialFen();

        this.acceptGameState(gameStateFull.getGameState());
    }

    private void acceptGameState(final GameState gameState) {
        try {
            preAcceptGameStateHook.accept(gameState);
        } catch (IllegalStateException e) {
            log.debug("preAcceptGameStateHook threw IllegalStateException {}", e.getMessage());
            log.trace(e);
            return;
        }
        lastGameState = gameState;

        final Bitboard board;
        final boolean defaultFen = initialFen == null || "startpos".equals(initialFen) || initialFen.trim().isEmpty();
        if (defaultFen) {
            board = UciMove.getBoard(gameState.getMoves());
        } else {
            board = UciMove.getBoard(gameState.getMoves(), Fen.parse(initialFen));
        }

        if (board.getActivePlayer() != myColor) {
            log.debug("Not calculating move for opponent");
            return;
        }

        executorService.execute(() -> {
            if (defaultFen) {
                engine.positionFromDefault(gameState.getMoves());
            } else {
                engine.position(initialFen, gameState.getMoves());
            }

            final Go go = Go.builder()
                            .blackTime(gameState.getBlackTime())
                            .whiteTime(gameState.getWhiteTime())
                            .blackIncrement(gameState.getBlackIncrement())
                            .whiteIncrement(gameState.getWhiteIncrement()).build();

            engine.go(go);
        });
    }

    private void acceptChatLine(final ChatLine chatLine) {
        if (preAcceptChatLine != null) {
            preAcceptChatLine.accept(chatLine);
        }
        chatMessageEventHandler.accept(chatLine,
                                       new LichessChatContext(this::writeInChat, engine, initialGameState, lastGameState));
    }

    private void writeInChat(final LichessChatResponse response) {
        executorService.execute(() -> {
            final HttpUriRequest request = HttpUtil.createAuthorizedPostRequest(Endpoints.writeInChat(gameId, response.getRoom(), response
                    .getMessage()), apiToken);

            try (final CloseableHttpResponse ignored = httpClient.execute(request)) {
            } catch (final IOException e) {
                log.error("", e);
            }
        });
    }

    @Override
    public void run() {
        log.info("Starting stream for game " + gameId);
        try (final CloseableHttpAsyncClient client = HttpAsyncClients.createDefault()) {
            client.start();

            final HttpAsyncRequestProducer request = HttpUtil.createAuthenticatedRequestProducer(Endpoints.gameStream(gameId), apiToken);

            final Future<Boolean> callback = client.execute(
                    request,
                    new GameStateResponseConsumer(
                            this::acceptFullGameState,
                            this::acceptGameState,
                            this::acceptChatLine
                    ),
                    null
            );

            try {
                callback.get();
            } catch (InterruptedException | ExecutionException e) {
                log.error("", e);
            }
        } catch (final IOException e) {
            log.error("", e);
        }

        log.info("Closing stream for game " + gameId);
    }

    @Override
    public void bestMove(final UciMove move) {
        executorService.execute(() -> {
            final HttpUriRequest request = HttpUtil.createAuthorizedPostRequest(Endpoints.makeMove(gameId, move), apiToken);

            log.info("Trying to play move " + move + " in game " + gameId + "...");
            try (CloseableHttpResponse httpResponse = httpClient.execute(request)) {
                final HttpEntity entity = httpResponse.getEntity();

                if (httpResponse.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                    log.info("Played move " + move + " in game " + gameId);
                } else {
                    log.warn("Failed to play move " + move + " in game " + gameId + ": " + EntityUtils.toString(entity));
                }

                EntityUtils.consume(entity);

                log.trace("Consumed entity");
            } catch (final ClientProtocolException e) {
                log.error("Failed to play move " + move + " in game " + gameId, e);
            } catch (final IOException e) {
                log.error("", e);
            }
        });
    }

    public void setPreAcceptFullGameStateHook(Consumer<GameStateFull> consumer) {
        this.preAcceptFullGameStateHook = consumer;
    }

    public void setPreAcceptGameStateHook(Consumer<GameState> consumer) {
        this.preAcceptGameStateHook = consumer;
    }

    public void setPreAcceptChatLine(Consumer<ChatLine> consumer) {
        this.preAcceptChatLine = consumer;
    }
}
