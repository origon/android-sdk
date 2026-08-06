package origon.example.android

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import origon.example.android.services.Http
import origon.example.android.services.SDKManager

/**
 * Owns the single app-wide [SDKManager]. Every screen reaches the SDK through
 * `(application as OrigonExampleApp).sdk`.
 *
 * It lives here rather than on the Activity because there must be exactly one
 * `OrigonClient` ever: an Activity-scoped manager is rebuilt on every
 * configuration change the manifest does not name — locale, font scale,
 * density, "Don't keep activities" — and once the user reconnects there are two
 * live clients, the second stealing the first's session while the first keeps
 * polling a handle nobody can reach.
 */
class OrigonExampleApp : Application(), SingletonImageLoader.Factory {

    lateinit var sdk: SDKManager
        private set

    override fun onCreate() {
        super.onCreate()
        // if (BuildConfig.DEBUG) {
        //     OrigonClient.initLogging()
        // }
        sdk = SDKManager(applicationContext)
    }

    /**
     * Coil's one loader, built on the app's existing OkHttp client.
     *
     * `OkHttpNetworkFetcherFactory()` with no argument constructs a **fresh**
     * `OkHttpClient` — a second connection pool and dispatcher thread pool for
     * the sake of attachment thumbnails. Handing it [Http.shared] keeps one of
     * each.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { Http.shared })) }
            .crossfade(true)
            .build()
}
