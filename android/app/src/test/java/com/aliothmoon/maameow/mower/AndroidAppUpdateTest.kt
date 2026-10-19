package com.aliothmoon.maameow.mower

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.provider.Settings
import com.aliothmoon.maameow.BuildConfig
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AndroidAppUpdateTest {
    private lateinit var activity: Activity
    private lateinit var apk: File
    private lateinit var id: String
    private lateinit var incoming: PackageInfo

    @Before fun preparePackage() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val bytes = "verified APK fixture".toByteArray()
        id = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        apk = File(activity.filesDir, "mower-data/tmp/updates/4e21008231518eabb448/android-apk/$id.apk")
        apk.parentFile!!.mkdirs()
        apk.writeBytes(bytes)
        val installed = shadowOf(activity.packageManager).getInternalMutablePackageInfo(activity.packageName)
        installed.signatures = arrayOf(Signature("aabbcc"))
        incoming = PackageInfo().apply {
            packageName = activity.packageName
            versionName = "0.3.0"
            setLongVersionCode(BuildConfig.VERSION_CODE.toLong() + 1)
            signatures = arrayOf(Signature("aabbcc"))
        }
        shadowOf(activity.packageManager).setPackageArchiveInfo(apk.path, incoming)
    }

    @Test fun verifiesDownloadedPackageAtTheSharedCachePath() {
        assertEquals(apk, AndroidAppUpdate.downloadedFile(activity, id))
        val checked = AndroidAppUpdate.inspectFile(activity, apk, id)
        assertEquals("0.3.0", checked.getString("version"))
        assertEquals(BuildConfig.VERSION_CODE.toLong() + 1, checked.getLong("version_code"))
        assertThrows(IllegalArgumentException::class.java) { AndroidAppUpdate.downloadedFile(activity, "../escape") }
    }

    @Test fun rejectsChangedContentsWrongPackageSignatureAndDowngrade() {
        apk.appendText("tampered")
        assertThrows(IllegalStateException::class.java) { AndroidAppUpdate.inspectFile(activity, apk, id) }
        apk.writeText("verified APK fixture")
        incoming.packageName = "other.application"
        assertThrows(IllegalStateException::class.java) { AndroidAppUpdate.inspectFile(activity, apk, id) }
        incoming.packageName = activity.packageName
        incoming.signatures = arrayOf(Signature("ddeeff"))
        assertThrows(IllegalStateException::class.java) { AndroidAppUpdate.inspectFile(activity, apk, id) }
        incoming.signatures = arrayOf(Signature("aabbcc"))
        incoming.setLongVersionCode(BuildConfig.VERSION_CODE.toLong() - 1)
        assertThrows(IllegalStateException::class.java) { AndroidAppUpdate.inspectFile(activity, apk, id) }
    }

    @Test fun requestsInstallationPermissionWithoutOpeningInstaller() {
        shadowOf(activity.packageManager).setCanRequestPackageInstalls(false)
        assertTrue(activity.installMowerApk(apk).contains("允许安装"))
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
        assertEquals("package:${activity.packageName}", intent.data.toString())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun settingsAndOtherActivitiesCanOpenTheSystemInstallerWithAContentUri() {
        shadowOf(activity.packageManager).setCanRequestPackageInstalls(true)
        assertTrue(activity.installMowerApk(apk).contains("系统安装器"))
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertEquals("content", intent.data!!.scheme)
        assertEquals("${activity.packageName}.updates", intent.data!!.authority)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertArrayEquals(apk.readBytes(), activity.contentResolver.openInputStream(intent.data!!)!!.use { it.readBytes() })
    }
}
