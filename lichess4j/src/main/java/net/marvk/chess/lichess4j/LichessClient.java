package net.marvk.chess.lichess4j;

import lombok.extern.log4j.Log4j2;
import net.marvk.chess.lichess4j.model.*;
import net.marvk.chess.lichess4j.util.HttpUtil;
import net.marvk.chess.uci4j.EngineFactory;
import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.http.impl.nio.conn.PoolingNHttpClientConnectionManager;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.apache.http.nio.conn.NHttpClientConnectionManager;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.nio.reactor.ConnectingIOReactor;
import org.apache.http.nio.reactor.IOReactorException;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.util.EntityUtils;
import org.codehaus.plexus.util.StringUtils;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Log4j2
public class LichessClient implements AutoCloseable {

    private final CloseableHttpAsyncClient asyncClient;
    private final CloseableHttpClient httpClient;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Set<Perf> allowedPerfs;
    private final boolean allowAllPerfsOnCasual;
    private final EngineFactory engineFactory;
    private final ChatMessageEventHandler eventHandler;
    private final String accountName;
    private final String apiToken;

    /**
     * Executed at the start of {@link #handleChallenge(Challenge)}<br>
     * Use {@link #setPreHandleChallengeHook(Consumer)}
     */
    private Consumer<Challenge> preHandleChallengeHook = o -> {};

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

    /**
     * Executed at the end of {@link #startEventHttpStream(HttpAsyncRequestProducer)}<br>
     * Use {@link #setPostEventHttpStreamHook(Runnable)}
     */
    private Runnable postEventHttpStreamHook = () -> {};

    /**
     * Executed after receiving an unhandled event type.<br>
     * A couple events aren't handled by default in the original source code of this project.
     */
    private Consumer<EventResponse> otherEventTypeHook = o -> {};

    /**
     * Executed after receiving a malformed event type, typically when a {@link EventResponse.Type} is null.
     */
    private Consumer<EventResponse> malformedEventHook = o -> {};

    LichessClient(final String accountName,
                  final String apiToken,
                  final Collection<Perf> allowedPerfs,
                  final boolean allowAllPerfsOnCasual,
                  final EngineFactory engineFactory,
                  final ChatMessageEventHandler eventHandler) throws LichessClientInstantiationException {
        this.accountName = accountName;
        this.apiToken = apiToken;
        this.allowedPerfs = Set.copyOf(allowedPerfs);
        this.allowAllPerfsOnCasual = allowAllPerfsOnCasual;
        this.engineFactory = engineFactory;
        this.eventHandler = eventHandler;

        final SSLContext build = createSslContext();

        this.httpClient = HttpClientBuilder.create()
                                           .setSSLContext(build)
                                           .setSSLHostnameVerifier(new NoopHostnameVerifier())
                                           .build();

        final IOReactorConfig ioReactorConfig = IOReactorConfig.custom().setIoThreadCount(10).build();
        final ConnectingIOReactor ioReactor = createIoReactor(ioReactorConfig);
        final NHttpClientConnectionManager connectionManager = new PoolingNHttpClientConnectionManager(ioReactor);

        this.asyncClient = HttpAsyncClients.custom()
                                           .setConnectionManager(connectionManager)
                                           .setMaxConnTotal(100)
                                           .build();
    }

    private static SSLContext createSslContext() throws LichessClientInstantiationException {
        try {
            return new SSLContextBuilder().loadTrustMaterial(null, (c, at) -> true).build();
        } catch (final NoSuchAlgorithmException | KeyManagementException | KeyStoreException e) {
            throw new LichessClientInstantiationException(e);
        }
    }

    private static ConnectingIOReactor createIoReactor(final IOReactorConfig ioReactorConfig) throws LichessClientInstantiationException {
        try {
            return new DefaultConnectingIOReactor(ioReactorConfig);
        } catch (final IOReactorException e) {
            throw new LichessClientInstantiationException(e);
        }
    }

    public void start() throws LichessClientOperationException {
        asyncClient.start();

        final HttpAsyncRequestProducer request = HttpUtil.createAuthenticatedRequestProducer(Endpoints.eventStream(), apiToken);

        try {
            startEventHttpStream(request);
        } catch (final InterruptedException | ExecutionException e) {
            throw new LichessClientOperationException(e);
        }
    }

    private void startEventHttpStream(final HttpAsyncRequestProducer request) throws InterruptedException, ExecutionException {
        log.info("Starting event stream");

        final Future<Boolean> execute = asyncClient.execute(
                request, 
                new EventResponseConsumer(this::handleChallenge, this::startGameHttpStream, otherEventTypeHook, malformedEventHook),
                null
        );

        execute.get();
        log.info("Closing event stream");
        postEventHttpStreamHook.run();
    }

    private void handleChallenge(final Challenge challenge) {
        final String gameId = challenge.getId();
        final Perf perf = challenge.getPerf();
        final String rated = challenge.getRated() ? "rated" : "casual";
        String endpoint = null;

        // Injecting logic from outside applications.
        try {
            preHandleChallengeHook.accept(challenge);
        } catch (IllegalStateException e) {
            log.debug("preHandleChallengeHook threw IllegalStateException {}", e.getMessage());
            endpoint = Endpoints.declineChallenge(gameId);
        }
        if (StringUtils.equals(endpoint, Endpoints.declineChallenge(gameId))) {
            log.info("Declining " + rated + " challenge " + gameId + " with perf " + perf + " as preHandleChallengeHook denied it.");
        }
        // Followed by original logic.
        else if ((allowAllPerfsOnCasual && !challenge.getRated()) || allowedPerfs.contains(perf)) {
            log.info("Accepting " + rated + " challenge " + gameId + " with perf " + perf);
            endpoint = Endpoints.acceptChallenge(gameId);
        } else {
            log.info("Declining " + rated + " challenge " + challenge.getId() + " due to perf mismatch, allowed perfs are " + allowedPerfs + " but got " + perf);
            endpoint = Endpoints.declineChallenge(gameId);
        }

        final String finalEndpoint = endpoint;
        executor.execute(() -> {
            final HttpUriRequest request = HttpUtil.createAuthorizedPostRequest(finalEndpoint, apiToken);

            log.trace("Trying to handle challenge " + gameId + "...");

            try (final CloseableHttpResponse httpResponse = httpClient.execute(request)) {
                final HttpEntity entity = httpResponse.getEntity();

                if (httpResponse.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                    log.info("Handled challenge " + gameId);
                } else {
                    log.warn("Failed to handle challenge " + gameId + ": " + EntityUtils.toString(entity));
                }

                EntityUtils.consume(entity);

                log.trace("Consumed entity");
            } catch (final ClientProtocolException e) {
                log.error("Failed to handle challenge " + gameId, e);
            } catch (final IOException e) {
                log.error("", e);
            }
        });
    }

    private void startGameHttpStream(final Game game) {
        final Supplier<GameThread> instantiateGameThread = () -> {
            final GameThread gameThread = new GameThread(accountName,
                                                         apiToken,
                                                         game.getId(),
                                                         httpClient,
                                                         executor,
                                                         engineFactory,
                                                         eventHandler);
            gameThread.setPreAcceptFullGameStateHook(preAcceptFullGameStateHook);
            gameThread.setPreAcceptGameStateHook(preAcceptGameStateHook);
            gameThread.setPreAcceptChatLine(preAcceptChatLine);
            return gameThread;
        };
        executor.execute(instantiateGameThread.get());
    }

    @Override
    public void close() throws IOException {
        asyncClient.close();
        httpClient.close();
    }

    private static List<Perf> mergePerfsList(final Perf perf, final Perf[] perfs) {
        return Stream.concat(Stream.of(perf), Arrays.stream(perfs)).collect(Collectors.toList());
    }

    public void setPreHandleChallengeHook(Consumer<Challenge> consumer) {
        this.preHandleChallengeHook = consumer;
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

    public void setPostEventHttpStreamHook(final Runnable postEventHttpStreamHook) {
        this.postEventHttpStreamHook = postEventHttpStreamHook;
    }

    public void setOtherEventTypeHook(final Consumer<EventResponse> otherEventTypeHook) {
        this.otherEventTypeHook = otherEventTypeHook;
    }

    public void setMalformedEventHook(final Consumer<EventResponse> malformedEventHook) {
        this.malformedEventHook = malformedEventHook;
    }
}
