package manana.sync.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer

/**
 * Entry point.
 *
 * Binds `127.0.0.1:8080` by default and speaks plain HTTP. **That is on purpose and it is not a
 * finished deployment**: this process must sit behind a reverse proxy that terminates TLS, because
 * bearer tokens and sealed envelopes both travel in the clear over a bare HTTP hop. Binding
 * loopback by default means the mistake of exposing it directly has to be made explicitly, by
 * setting `MANANA_HOST=0.0.0.0`, rather than by forgetting to prevent it. `server/README.md` has
 * the proxy configuration.
 *
 * Configuration comes from the environment -- see [ServerConfig.fromEnvironment]. An unparseable
 * value stops start-up rather than falling back to a default.
 */
fun main() {
    val config = ServerConfig.fromEnvironment(System.getenv())
    val clock = SystemClock
    val store = SyncStore.open(config.databasePath, clock, config.historyDepth)
    val log = RequestLog(debugEnabled = System.getenv("MANANA_DEBUG") == "1")
    val deps = ServerDeps(
        store = store,
        config = config,
        clock = clock,
        log = log,
        rateLimiter = RateLimiter(config.rateLimitPerMinute, config.rateLimitBurst, clock),
        pairingDepositLimiter = RateLimiter(
            config.pairingDepositPerMinute, config.pairingDepositBurst, clock,
        ),
    )

    if (config.host != "127.0.0.1" && config.host != "localhost") {
        log.warn(
            "binding a non-loopback address: this server speaks plain HTTP and must be behind a " +
                "TLS-terminating proxy"
        )
    }

    Runtime.getRuntime().addShutdownHook(Thread { store.close() })

    embeddedServer(CIO, port = config.port, host = config.host) {
        syncModule(deps)
    }.start(wait = true)
}
