package com.rskusum.whocaller.core.permissions

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class AppPermissionTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    @Config(sdk = [26])
    fun oreoUsesPhoneStateForCallerId() {
        assertArrayEquals(arrayOf(Manifest.permission.READ_PHONE_STATE), AppPermission.CALLER_ID.runtimePermissions)
        assertFalse(AppPermission.CALLER_ID.usesCallScreeningRole)
        assertTrue(AppPermission.NOTIFICATIONS.runtimePermissions.isEmpty())
    }

    @Test
    @Config(sdk = [28])
    fun pieAlsoNeedsCallLogForTheNumber() {
        assertArrayEquals(
            arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG),
            AppPermission.CALLER_ID.runtimePermissions,
        )
    }

    @Test
    @Config(sdk = [34])
    fun modernAndroidUsesRoleAndNotificationPermission() {
        assertTrue(AppPermission.CALLER_ID.usesCallScreeningRole)
        assertTrue(AppPermission.CALLER_ID.runtimePermissions.isEmpty())
        assertArrayEquals(arrayOf(Manifest.permission.POST_NOTIFICATIONS), AppPermission.NOTIFICATIONS.runtimePermissions)
    }

    @Test
    @Config(sdk = [34])
    fun deniedThenGrantedThenRevoked() {
        val manager = PermissionManager(app)
        assertFalse(manager.isGranted(AppPermission.CONTACTS))
        assertEquals(PermissionStatus.NOT_REQUESTED, manager.status(null, AppPermission.CONTACTS))

        manager.markRequested(AppPermission.CONTACTS)
        assertEquals(PermissionStatus.DENIED, manager.status(null, AppPermission.CONTACTS))

        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
        assertEquals(PermissionStatus.GRANTED, manager.status(null, AppPermission.CONTACTS))

        shadowOf(app).denyPermissions(Manifest.permission.READ_CONTACTS)
        assertFalse(manager.isGranted(AppPermission.CONTACTS))
    }

    @Test
    @Config(sdk = [34])
    fun callerIdNotReadyWithoutRole() {
        assertFalse(PermissionManager(app).isCallerIdReady())
    }
}
