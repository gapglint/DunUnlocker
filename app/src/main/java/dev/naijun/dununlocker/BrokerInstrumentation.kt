package dev.naijun.dununlocker

import android.Manifest
import android.app.IActivityManager
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.system.Os
import android.telephony.SubscriptionManager
import android.util.Log
import dev.naijun.dununlocker.data.ApnOperations
import dev.naijun.dununlocker.data.CarrierPrivilegeManager
import dev.naijun.dununlocker.domain.model.ApnContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.lsposed.hiddenapibypass.HiddenApiBypass
import org.lsposed.lsparanoid.Obfuscate
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

const val UNLOCKER_TAG = "BrokerInstrumentation"

private const val PERMISSION_READ_PRIVILEGED_PHONE_STATE = "android.permission.READ_PRIVILEGED_PHONE_STATE"
private const val PERMISSION_MODIFY_PHONE_STATE = "android.permission.MODIFY_PHONE_STATE"
private const val OPERATION_KEY = "operation"
private const val OPERATION_CREATE = "create"
private const val OPERATION_COPY = "copy_apn"
private const val OPERATION_LIST = "list_apns"

@Obfuscate
class BrokerInstrumentation : Instrumentation() {

    override fun onCreate(arguments: Bundle?) {
        HiddenApiBypass.setHiddenApiExemptions("")

        super.onCreate(arguments)

        if (arguments == null) {
            Log.e(UNLOCKER_TAG, "Arguments is null")
            finish(-1, Bundle())
            return
        }

        val useAdbShell = arguments.getString("use_adb_shell") == "true" ||
            arguments.getBoolean("use_adb_shell", false)
        val hasShellWriteApn = CarrierPrivilegeManager.hasShellWriteApnPermission(context)

        try {
            val result = if (useAdbShell || !hasShellWriteApn) {
                executeWithAdbShell(arguments, hasShellWriteApn)
            } else {
                executeWithShizukuDelegation(arguments)
            }
            finish(0, result)
        } catch (e: Exception) {
            Log.e(UNLOCKER_TAG, "Error in BrokerInstrumentation", e)
            finish(-100, Bundle().apply {
                putString("error", e.message ?: "Unknown error")
            })
        }
    }

    private fun executeWithShizukuDelegation(arguments: Bundle): Bundle {
        val am = IActivityManager.Stub.asInterface(
            ShizukuBinderWrapper(
                SystemServiceHelper.getSystemService(Context.ACTIVITY_SERVICE)
            )
        )
        am.startDelegateShellPermissionIdentity(
            Os.getuid(),
            arrayOf(
                Manifest.permission.WRITE_APN_SETTINGS,
                PERMISSION_READ_PRIVILEGED_PHONE_STATE
            )
        )
        return try {
            runOperation(arguments)
        } finally {
            try {
                am.stopDelegateShellPermissionIdentity()
            } catch (e: Exception) {
                Log.w(UNLOCKER_TAG, "Failed to stop delegate permission: ${e.message}")
            }
        }
    }

    private fun executeWithAdbShell(arguments: Bundle, hasShellWriteApn: Boolean): Bundle {
        val automation = uiAutomation
        if (hasShellWriteApn) {
            automation.adoptShellPermissionIdentity(
                Manifest.permission.WRITE_APN_SETTINGS,
                PERMISSION_READ_PRIVILEGED_PHONE_STATE
            )
            return try {
                runOperation(arguments)
            } finally {
                automation.dropShellPermissionIdentity()
            }
        }

        val subId = parseSubId(arguments).let {
            if (it >= 0) it else SubscriptionManager.getDefaultDataSubscriptionId()
        }
        return runBlocking(Dispatchers.IO) {
            CarrierPrivilegeManager.withCarrierPrivileges(
                context = context,
                subId = subId,
                useShizuku = false,
                callingPackage = context.packageName,
                withShellIdentity = { action ->
                    automation.adoptShellPermissionIdentity(
                        PERMISSION_MODIFY_PHONE_STATE,
                        PERMISSION_READ_PRIVILEGED_PHONE_STATE
                    )
                    try {
                        action()
                    } finally {
                        automation.dropShellPermissionIdentity()
                    }
                }
            ) {
                runOperation(arguments, fallbackSubId = subId)
            }
        }
    }

    private fun runOperation(arguments: Bundle, fallbackSubId: Int = -1): Bundle {
        val operation = arguments.getString(OPERATION_KEY) ?: OPERATION_CREATE
        val subId = parseSubId(arguments).let { if (it >= 0) it else fallbackSubId }

        return when (operation) {
            OPERATION_LIST -> {
                val apns = ApnOperations.listApns(context, subId)
                val rows = ArrayList(
                    apns.map { apn ->
                        Bundle().apply {
                            putLong("id", apn.id)
                            putString("name", apn.name)
                            putString("apn", apn.apn)
                            putString("type", apn.type)
                            putBoolean("preferred", apn.isPreferred)
                        }
                    }
                )
                Bundle().apply {
                    putParcelableArrayList("apns", rows)
                    putString(
                        "apn_summary",
                        apns.joinToString(" | ") {
                            "#${it.id}:${it.name}(${it.apn})[${it.type}]${if (it.isPreferred) "*" else ""}"
                        }
                    )
                }
            }
            OPERATION_COPY -> {
                val sourceId = parseLongArg(arguments, "source_apn_id", -1L)
                val copyName = arguments.getString("copy_name")
                val copyId = ApnOperations.copyApnWithDun(context, subId, sourceId, copyName)
                Bundle().apply {
                    if (copyId != null) {
                        putLong("copied_apn_id", copyId)
                    }
                }
            }
            else -> {
                val apn = arguments.getString("apn") ?: ""
                if (apn.isEmpty()) {
                    throw IllegalArgumentException("APN address is empty")
                }

                val content = ApnContent(
                    name = arguments.getString("name") ?: "DUN",
                    numeric = arguments.getString("numeric") ?: "45005",
                    mcc = arguments.getString("mcc") ?: "450",
                    mnc = arguments.getString("mnc") ?: "05",
                    apn = apn,
                    type = arguments.getString("type") ?: "default,mms,supl,rcs,dun",
                    protocol = arguments.getString("protocol") ?: "IPV4V6",
                    mmsc = arguments.getString("mmsc") ?: "",
                    mmsProxy = arguments.getString("mms_proxy") ?: "",
                    mmsPort = arguments.getString("mms_port") ?: "",
                    roamingProtocol = arguments.getString("roaming_protocol") ?: "IPV4V6",
                    server = arguments.getString("server") ?: "*",
                    authType = arguments.getString("auth_type") ?: "0",
                    user = arguments.getString("user") ?: "",
                    password = arguments.getString("password") ?: ""
                )

                val insertedUri = ApnOperations.createApn(context, content, subId)
                Bundle().apply {
                    putString("inserted_uri", insertedUri.toString())
                }
            }
        }
    }

    private fun parseSubId(arguments: Bundle): Int {
        val rawString = arguments.getString("sub_id")
        if (rawString != null) {
            return rawString.toIntOrNull() ?: -1
        }
        return arguments.getInt("sub_id", -1)
    }

    private fun parseLongArg(arguments: Bundle, key: String, defaultValue: Long): Long {
        val rawString = arguments.getString(key)
        if (rawString != null) {
            return rawString.toLongOrNull() ?: defaultValue
        }
        return arguments.getLong(key, defaultValue)
    }
}
