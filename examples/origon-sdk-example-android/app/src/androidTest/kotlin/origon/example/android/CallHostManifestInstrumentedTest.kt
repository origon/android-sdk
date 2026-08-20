package origon.example.android

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import origon.example.android.services.CallForegroundService

class CallHostManifestInstrumentedTest {
    @Test
    fun microphoneHostIsPrivateTypedAndDeclaresAllPlatformPermissions() {
        val context = ApplicationProvider.getApplicationContext<OrigonExampleApp>()
        val packageManager = context.packageManager
        @Suppress("DEPRECATION")
        val service = packageManager.getServiceInfo(
            ComponentName(context, CallForegroundService::class.java),
            0,
        )
        @Suppress("DEPRECATION")
        val requested = packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        ).requestedPermissions.orEmpty().toSet()

        assertFalse(service.exported)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, service.foregroundServiceType)
        }
        assertTrue(Manifest.permission.RECORD_AUDIO in requested)
        assertTrue(Manifest.permission.FOREGROUND_SERVICE in requested)
        assertTrue(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE in requested)
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in requested)
    }
}
