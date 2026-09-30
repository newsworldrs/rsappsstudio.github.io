package com.rskusum.whocaller.core.permissions

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PermissionManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences("whocaller_permissions", Context.MODE_PRIVATE)

    fun isGranted(permission: AppPermission): Boolean = when {
        permission.usesCallScreeningRole -> hasCallScreeningRole()
        else -> permission.runtimePermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun isGranted(manifestPermission: String): Boolean =
        ContextCompat.checkSelfPermission(context, manifestPermission) == PackageManager.PERMISSION_GRANTED

    fun status(activity: Activity?, permission: AppPermission): PermissionStatus {
        if (isGranted(permission)) return PermissionStatus.GRANTED
        if (!wasRequested(permission)) return PermissionStatus.NOT_REQUESTED
        if (permission.usesCallScreeningRole || activity == null) return PermissionStatus.DENIED
        val canAskAgain = permission.runtimePermissions.any {
            ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
        }
        return if (canAskAgain) PermissionStatus.DENIED else PermissionStatus.PERMANENTLY_DENIED
    }

    fun markRequested(permission: AppPermission) {
        prefs.edit().putBoolean(KEY_PREFIX + permission.name, true).apply()
    }

    fun wasRequested(permission: AppPermission): Boolean = prefs.getBoolean(KEY_PREFIX + permission.name, false)

    fun hasCallScreeningRole(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
        return roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
            roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }

    /** Intent that asks the user to make WhoCaller the call-screening app, or null if unsupported. */
    fun callScreeningRoleIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return null
        if (!roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) return null
        return roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
    }

    /** Caller ID works if we hold the role (29+) or the legacy phone-state permissions (26–28). */
    fun isCallerIdReady(): Boolean = isGranted(AppPermission.CALLER_ID)

    fun appSettingsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** True if a manifest permission is declared by this build (used for optional features like SMS inbox). */
    fun isDeclared(manifestPermission: String): Boolean = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        }
        info.requestedPermissions?.contains(manifestPermission) == true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private companion object {
        const val KEY_PREFIX = "requested_"
    }
}
