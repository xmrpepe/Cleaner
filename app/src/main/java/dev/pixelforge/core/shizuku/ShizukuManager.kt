package dev.pixelforge.core.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import dev.pixelforge.BuildConfig
import dev.pixelforge.core.util.ShellResult
import dev.pixelforge.shizuku.IShellService
import dev.pixelforge.shizuku.ShellService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

enum class ShizukuState(val label: String) {
    NOT_INSTALLED("Shizuku app not installed"),
    NOT_RUNNING("Shizuku installed but not running"),
    NO_PERMISSION("Permission not granted to PixelForge"),
    READY("Connected"),
    DISABLED("Disabled in PixelForge settings"),
}

/**
 * Optional privileged access through Shizuku. Shizuku started via Wireless debugging runs with
 * the ADB shell UID — it can do what `adb shell` can (pm, settings, cmd, dumpsys), not root.
 */
class ShizukuManager(private val context: Context) {
    private val _state = MutableStateFlow(ShizukuState.NOT_RUNNING)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    @Volatile private var service: IShellService? = null
    @Volatile var enabled: Boolean = true
        set(value) {
            field = value
            refresh()
        }

    private val userServiceArgs = Shizuku.UserServiceArgs(ComponentName(context.packageName, ShellService::class.java.name))
        .daemon(false)
        .processNameSuffix("shell")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private var pendingBind: CompletableDeferred<IShellService?>? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = binder?.takeIf { it.pingBinder() }?.let { IShellService.Stub.asInterface(it) }
            service = s
            pendingBind?.complete(s)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        service = null
        refresh()
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    fun start() {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refresh()
    }

    fun refresh() {
        _state.value = computeState()
    }

    private fun computeState(): ShizukuState {
        if (!enabled) return ShizukuState.DISABLED
        val installed = try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        val alive = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
        if (!alive) return if (installed) ShizukuState.NOT_RUNNING else ShizukuState.NOT_INSTALLED
        if (Shizuku.isPreV11()) return ShizukuState.NOT_RUNNING
        return if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) ShizukuState.READY else ShizukuState.NO_PERMISSION
    }

    fun requestPermission() {
        try {
            if (Shizuku.pingBinder()) Shizuku.requestPermission(REQUEST_CODE)
        } catch (t: Throwable) {
            Log.w(TAG, "requestPermission failed", t)
        }
    }

    /** Identity of the privileged process: 2000 = shell (ADB), 0 = root. */
    fun serverUid(): Int? = try { if (Shizuku.pingBinder()) Shizuku.getUid() else null } catch (_: Throwable) { null }

    fun serverVersion(): Int? = try { if (Shizuku.pingBinder()) Shizuku.getVersion() else null } catch (_: Throwable) { null }

    private suspend fun obtainService(): IShellService? {
        if (state.value != ShizukuState.READY) return null
        service?.let { if (it.asBinder().pingBinder()) return it }
        val deferred = CompletableDeferred<IShellService?>()
        pendingBind = deferred
        withContext(Dispatchers.Main) {
            try {
                Shizuku.bindUserService(userServiceArgs, connection)
            } catch (t: Throwable) {
                Log.w(TAG, "bindUserService failed", t)
                deferred.complete(null)
            }
        }
        return withTimeoutOrNull(10_000) { deferred.await() }
    }

    suspend fun exec(command: String, timeoutMs: Long = 30_000): ShellResult? = withContext(Dispatchers.IO) {
        val s = obtainService() ?: return@withContext null
        try {
            val raw = s.exec(command, timeoutMs) ?: return@withContext null
            val nl = raw.indexOf('\n')
            val code = (if (nl >= 0) raw.substring(0, nl) else raw).trim().toIntOrNull() ?: -1
            ShellResult(code, if (nl >= 0) raw.substring(nl + 1) else "")
        } catch (t: Throwable) {
            Log.w(TAG, "exec failed", t)
            service = null
            null
        }
    }

    /** Blocking file read through the shell process; safe to call from monitor threads. */
    fun readFileBlocking(path: String, maxBytes: Int = 256 * 1024): String? {
        if (state.value != ShizukuState.READY) return null
        val s = service ?: return null
        return try { s.readFile(path, maxBytes) } catch (_: Throwable) { null }
    }

    /** Ensures the user service is bound so blocking reads can be served. */
    suspend fun warmUp() {
        obtainService()
    }

    companion object {
        private const val TAG = "ShizukuManager"
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val REQUEST_CODE = 4107
    }
}
