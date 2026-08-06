package origon.example.android.services

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The app's one OkHttp client.
 *
 * A client owns a connection pool and a dispatcher thread pool, and OkHttp's
 * own guidance is one per application. Two callers share it: the attachment
 * download helper and the PDF preview's fetch-to-cache. coil takes this same
 * instance (see [origon.example.android.OrigonExampleApp]) rather than
 * constructing its own, which is what a bare `OkHttpNetworkFetcherFactory()`
 * would do.
 *
 * The Origon SDK does **not** go through this — it owns its own transport in
 * native code. This client only fetches attachment bytes, whose URLs the
 * server mints on a route that needs no auth header.
 */
object Http {
    val shared: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
