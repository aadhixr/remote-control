package com.example.remotecontrol

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * UI-visible state published by the running service so [MainActivity] can render
 * connection status and the session id without binding to the service.
 */
object RemoteControlState {
    val isConnected = kotlinx.coroutines.flow.MutableStateFlow(false)
    val sessionId = kotlinx.coroutines.flow.MutableStateFlow("")
    val status = kotlinx.coroutines.flow.MutableStateFlow("Idle")
    val lastMessage = kotlinx.coroutines.flow.MutableStateFlow("")
}

/**
 * Foreground service that keeps one OkHttp WebSocket alive and executes the
 * remote command protocol:
 *
 *   {"action":"click","x":<opt>,"y":<opt>}   -> tap (random coords when x/y absent)
 *   {"action":"screenshot"}                  -> JPEG (base64) sent back over the socket
 *   {"action":"location"}                    -> last known / fresh GPS fix
 *   {"action":"kill"}                        -> graceful shutdown + process exit
 *   {"action":"ping"}                        -> {"type":"pong"}
 *
 * Reconnection: exponential backoff (1s -> 30s, + jitter), reset on every
 * successful open, plus an immediate fast retry when the default network comes
 * back. A generation counter makes stale socket callbacks harmless.
 */
class RemoteControlService : Service() {

    companion object {
        const val PREFS = "remote_control"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_ENABLED = "enabled"
        const val KEY_SESSION_ID = "session_id"
        const val DEFAULT_URL =
            "wss://remote-control-relay.adhiphub-rc.workers.dev/ws"
        const val ACTION_ATTACH_PROJECTION =
            "com.example.remotecontrol.ATTACH_PROJECTION"

        /** Consent result handed over by MainActivity (resultCode to data). */
        @Volatile
        @JvmStatic
        var pendingProjection: Pair<Int, Intent>? = null

        private const val NOTIF_ID = 42
        private const val CHANNEL_ID = "remote_control"
        private const val MAX_BACKOFF_MS = 30_000L
        private const val MAX_SHOT_DIM = 1280
        private const val SHOT_QUALITY = 70
        private const val SHOT_WAIT_TRIES = 40
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val projectionLock = Any()

    private lateinit var prefs: SharedPreferences
    private lateinit var client: OkHttpClient

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var webSocketGeneration = 0
    @Volatile private var isConnecting = false
    @Volatile private var shuttingDown = false
    @Volatile private var foregroundStarted = false
    @Volatile private var notifText = "Starting…"

    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var sessionId = ""

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (shuttingDown || RemoteControlState.isConnected.value) return
            // Back off is pointless once the network itself just returned.
            reconnectJob?.cancel()
            reconnectAttempt = 0
            scope.launch {
                delay(500)
                connect()
            }
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        sessionId = prefs.getString(KEY_SESSION_ID, null)
            ?: Build.MODEL.contains('/')
                ? Build.MODEL.split('/').first()
                : Build.MODEL
       .also {
            prefs.edit().putString(KEY_SESSION_ID, it).apply()
        }
        RemoteControlState.sessionId.value = sessionId

        createNotificationChannel()
        if (!startAsForeground("Starting…")) {
            // Cannot hold a typed foreground service (permissions revoked).
            stopSelf()
            return
        }

        if (!prefs.getBoolean(KEY_ENABLED, true)) {
            stopSelf()
            return
        }

        client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // WebSockets are kept alive by pings
            .writeTimeout(10, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        runCatching {
            getSystemService(ConnectivityManager::class.java)
                .registerDefaultNetworkCallback(networkCallback)
        }

        RemoteControlState.status.value = "Connecting…"
        connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ATTACH_PROJECTION -> {
                val pending = pendingProjection
                if (pending != null) {
                    pendingProjection = null
                    attachProjection(pending.first, pending.second)
                }
            }
            else -> updateNotification(notifText)
        }
        // Sticky: Android restarts the service (and therefore the socket) if the
        // process is killed while a session is enabled.
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onDestroy() {
        shuttingDown = true
        reconnectJob?.cancel()
        scope.cancel()
        runCatching { webSocket?.cancel() }
        releaseProjection()
        runCatching {
            getSystemService(ConnectivityManager::class.java)
                .unregisterNetworkCallback(networkCallback)
        }
        if (::client.isInitialized) {
            runCatching { client.dispatcher.cancelAll() }
            runCatching { client.connectionPool.evictAll() }
        }
        RemoteControlState.isConnected.value = false
        if (RemoteControlState.status.value != "Stopped") {
            RemoteControlState.status.value = "Stopped"
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------- WebSocket core

    private fun connect() {
        if (shuttingDown || isConnecting) return
        if (RemoteControlState.isConnected.value) return
        if (::client.isInitialized.not()) return
        if (!prefs.getBoolean(KEY_ENABLED, true)) return

        isConnecting = true
        try {
            val url = normalizeWsUrl(prefs.getString(KEY_SERVER_URL, DEFAULT_URL) ?: DEFAULT_URL)
            val request = Request.Builder()
                .url(url)
                .header("X-Session-Id", sessionId)
                .build()
            val generation = ++webSocketGeneration
            runCatching { webSocket?.cancel() } // drop any stale socket
            webSocket = client.newWebSocket(request, socketListener(generation))
            RemoteControlState.status.value = "Connecting…"
            updateNotification("Connecting…")
        } catch (e: Exception) {
            isConnecting = false
            RemoteControlState.status.value = "Invalid server URL: ${e.message}"
            scheduleReconnect()
        }
    }

    /** OkHttp's HttpUrl only accepts http/https; ws/wss are mapped 1:1. */
    private fun normalizeWsUrl(raw: String): String = when {
        raw.startsWith("ws://") -> "http://" + raw.removePrefix("ws://")
        raw.startsWith("wss://") -> "https://" + raw.removePrefix("wss://")
        else -> raw
    }

    private fun socketListener(generation: Int) = object : WebSocketListener() {

        override fun onOpen(socket: WebSocket, response: Response) {
            if (generation != webSocketGeneration) {
                runCatching { socket.close(1000, "stale") }
                return
            }
            isConnecting = false
            webSocket = socket
            reconnectAttempt = 0
            RemoteControlState.isConnected.value = true
            RemoteControlState.status.value = "Connected"
            updateNotification("Connected · session $sessionId")
            socket.send(helloJson())
        }

        override fun onMessage(socket: WebSocket, text: String) {
            if (generation != webSocketGeneration) return
            handleCommand(text)
        }

        override fun onClosing(socket: WebSocket, code: Int, reason: String) {
            runCatching { socket.close(code, reason) }
                .onFailure { runCatching { socket.cancel() } }
        }

        override fun onClosed(socket: WebSocket, code: Int, reason: String) {
            handleDisconnect(generation, "closed ($code)")
        }

        override fun onFailure(socket: WebSocket, t: Throwable, response: Response?) {
            handleDisconnect(generation, t.message ?: t.javaClass.simpleName)
        }
    }

    private fun handleDisconnect(generation: Int, reason: String) {
        if (shuttingDown || generation != webSocketGeneration) return
        isConnecting = false
        RemoteControlState.isConnected.value = false
        RemoteControlState.status.value = "Disconnected"
        updateNotification("Disconnected ($reason) – retrying…")
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (shuttingDown) return
        reconnectJob?.cancel()
        val attempt = reconnectAttempt
        reconnectAttempt = attempt + 1
        val base = min(MAX_BACKOFF_MS, 1000L shl attempt.coerceAtMost(5)) // 1s,2s,4s…capped 30s
        val waitMs = base + Random.nextLong(0, base / 2 + 1)             // +/- 50% jitter
        RemoteControlState.status.value = "Reconnecting in ${waitMs / 1000}s…"
        updateNotification("Reconnecting in ${waitMs / 1000}s…")
        reconnectJob = scope.launch {
            delay(waitMs)
            connect()
        }
    }

    // ------------------------------------------------------------------ commands

    private fun handleCommand(raw: String) {
        RemoteControlState.lastMessage.value = raw.take(160)
        val json = try {
            JSONObject(raw)
        } catch (e: Exception) {
            sendError(null, "invalid JSON")
            return
        }
        val action = json.optString("action")
        val id: Any? = if (json.isNull("id")) null else json.opt("id")
        when (action) {
            "click" -> handleClick(json, id)
            "screenshot" -> handleScreenshot(id)
            "location" -> handleLocation(id)
            "kill" -> handleKill(id)
            "ping" -> sendJson {
                put("type", "pong")
                id?.let { put("id", it) }
            }
            else -> sendError(action.ifEmpty { null }, "unsupported action", id)
        }
    }

    private fun handleClick(json: JSONObject, id: Any?) {
        val dm = resources.displayMetrics
        val requestedX = json.optDouble("x", Double.NaN)
            .takeIf { it.isFinite() }?.toFloat()
            ?: Random.nextInt(0, max(1, dm.widthPixels)).toFloat()
        val requestedY = json.optDouble("y", Double.NaN)
            .takeIf { it.isFinite() }?.toFloat()
            ?: Random.nextInt(0, max(1, dm.heightPixels)).toFloat()
        val x = requestedX.coerceIn(0f, (dm.widthPixels - 1).toFloat())
        val y = requestedY.coerceIn(0f, (dm.heightPixels - 1).toFloat())

        val service = TapAccessibilityService.instance
        if (service == null) {
            sendError(
                "click",
                "tap service disabled – enable \"Remote Control taps\" in Settings > Accessibility",
                id
            )
            return
        }
        service.tap(x, y) { ok ->
            if (ok) {
                sendJson {
                    put("type", "result")
                    put("action", "click")
                    put("x", x.toDouble())
                    put("y", y.toDouble())
                    put("ok", true)
                    id?.let { put("id", it) }
                }
            } else {
                sendError("click", "gesture cancelled by the system", id)
            }
        }
    }

    private fun handleScreenshot(id: Any?) {
        scope.launch {
            try {
                val proj = ensureProjection()
                if (proj == null) {
                    sendError(
                        "screenshot",
                        "screen capture not granted – tap \"Grant screen capture\" in the app",
                        id
                    )
                    return@launch
                }
                val bitmap = captureScreen(proj)
                if (bitmap == null) {
                    sendError("screenshot", "capture failed", id)
                    return@launch
                }
                val width = bitmap.width
                val height = bitmap.height
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, SHOT_QUALITY, out)
                bitmap.recycle()
                val payload = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                sendJson {
                    put("type", "result")
                    put("action", "screenshot")
                    put("format", "jpeg")
                    put("width", width)
                    put("height", height)
                    put("data", payload)
                    id?.let { put("id", it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendError("screenshot", e.message ?: "capture failed", id)
            }
        }
    }

    private fun handleLocation(id: Any?) {
        scope.launch {
            try {
                val granted = arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ).any {
                    ContextCompat.checkSelfPermission(
                        this@RemoteControlService, it
                    ) == PackageManager.PERMISSION_GRANTED
                }
                if (!granted) {
                    sendError("location", "location permission not granted", id)
                    return@launch
                }
                val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val fix = lastKnownLocation(lm) ?: requestFreshLocation(lm)
                if (fix == null) {
                    sendError("location", "no location available", id)
                    return@launch
                }
                sendJson {
                    put("type", "result")
                    put("action", "location")
                    put("lat", fix.latitude)
                    put("lon", fix.longitude)
                    put("accuracy", fix.accuracy.toDouble())
                    put("provider", fix.provider ?: "unknown")
                    put("time", fix.time)
                    id?.let { put("id", it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendError("location", e.message ?: "location failed", id)
            }
        }
    }

    private fun handleKill(id: Any?) {
        sendJson {
            put("type", "ack")
            put("action", "kill")
            id?.let { put("id", it) }
        }
        shutdown(exitProcess = true)
    }

    // ----------------------------------------------------------- location helpers

    private fun lastKnownLocation(lm: LocationManager): Location? {
        val candidates = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).filter { provider ->
            runCatching { lm.isProviderEnabled(provider) }.getOrDefault(false)
        }
        return candidates.mapNotNull { provider ->
            runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }
    }

    /** Falls back to a single fresh fix with a 10s cap when last-known is missing. */
    private suspend fun requestFreshLocation(lm: LocationManager): Location? {
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { p ->
                lm.allProviders.contains(p) &&
                    runCatching { lm.isProviderEnabled(p) }.getOrDefault(false)
            }
            ?: lm.allProviders.firstOrNull {
                runCatching { lm.isProviderEnabled(it) }.getOrDefault(false)
            }
            ?: return null

        return withTimeoutOrNull(10_000L) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    val executor = Executor { command -> mainHandler.post(command) }
                    lm.getCurrentLocation(
                        provider,
                        signal,
                        executor,
                        Consumer { location ->
                            runCatching {
                                if (cont.isActive) cont.resume(location)
                            }
                        }
                    )
                }
            } else {
                suspendCancellableCoroutine<Location?> { cont ->
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            runCatching { lm.removeUpdates(this) }
                            runCatching {
                                if (cont.isActive) cont.resume(location)
                            }
                        }

                        override fun onProviderEnabled(provider: String) = Unit

                        override fun onProviderDisabled(provider: String) {
                            runCatching { lm.removeUpdates(this) }
                            runCatching {
                                if (cont.isActive) cont.resume(null)
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(
                            provider: String?,
                            status: Int,
                            extras: Bundle?
                        ) = Unit
                    }
                    cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
                    runCatching {
                        lm.requestSingleUpdate(provider, listener, mainHandler.looper)
                    }.onFailure {
                        runCatching {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------- media projection

    private fun ensureProjection(): MediaProjection? {
        projection?.let { return it }
        val pending = pendingProjection ?: return null
        pendingProjection = null
        return attachProjection(pending.first, pending.second)
    }

    private fun attachProjection(resultCode: Int, data: Intent): MediaProjection? =
        synchronized(projectionLock) {
            try {
                releaseProjectionLocked()
                // Android 14+: the FGS must carry the mediaProjection type *before*
                // MediaProjectionManager.getMediaProjection() is called.
                ServiceCompat.startForeground(
                    this,
                    NOTIF_ID,
                    buildNotification("Screen capture granted"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
                foregroundStarted = true
                notifText = "Screen capture granted"

                val pm = getSystemService(MediaProjectionManager::class.java)
                val mp = pm.getMediaProjection(resultCode, data) ?: return@synchronized null
                val callback = object : MediaProjection.Callback() {
                    override fun onStop() = releaseProjection()
                }
                mp.registerCallback(callback, mainHandler)
                projection = mp
                projectionCallback = callback
                updateNotification("Screen capture granted · session $sessionId")
                mp
            } catch (e: Exception) {
                RemoteControlState.lastMessage.value = "projection error: ${e.message}"
                null
            }
        }

    private fun releaseProjection() = synchronized(projectionLock) { releaseProjectionLocked() }

    private fun releaseProjectionLocked() {
        val mp = projection
        val callback = projectionCallback
        projection = null
        projectionCallback = null
        if (mp != null) {
            if (callback != null) runCatching { mp.unregisterCallback(callback) }
            runCatching { mp.stop() }
            // Drop back to the location-only foreground type while we are idle.
            if (foregroundStarted && !shuttingDown) {
                runCatching {
                    ServiceCompat.startForeground(
                        this,
                        NOTIF_ID,
                        buildNotification(notifText),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    )
                }
            }
        }
    }

    private suspend fun captureScreen(proj: MediaProjection): Bitmap? {
        val dm = resources.displayMetrics
        val longEdge = max(dm.widthPixels, dm.heightPixels)
        // Capturing at a reduced size keeps the base64 payload modest for the
        // WebSocket frame (the virtual display is scaled up by the system).
        val scale = if (longEdge > MAX_SHOT_DIM) MAX_SHOT_DIM.toFloat() / longEdge else 1f
        val w = max(2, (dm.widthPixels * scale).toInt() and 1.inv())
        val h = max(2, (dm.heightPixels * scale).toInt() and 1.inv())

        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        var display: VirtualDisplay? = null
        try {
            display = proj.createVirtualDisplay(
                "remote-control-shot",
                w,
                h,
                dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null
            ) ?: return null

            var image: Image? = null
            var tries = 0
            while (image == null && tries < SHOT_WAIT_TRIES) {
                image = reader.acquireLatestImage()
                if (image == null) {
                    delay(50)
                    tries++
                }
            }
            val captured = image ?: return null
            return captured.use { toBitmap(it, w, h) }
        } finally {
            runCatching { display?.release() }
            runCatching { reader.close() }
        }
    }

    private fun toBitmap(image: Image, w: Int, h: Int): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = max(1, plane.pixelStride)
        val rowStride = plane.rowStride
        val padding = rowStride - pixelStride * w
        return if (padding <= 0) {
            buffer.rewind()
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            bitmap
        } else {
            // Row stride is padded: copy into a wider bitmap, then crop.
            val paddedWidth = w + padding / pixelStride
            buffer.rewind()
            val padded = Bitmap.createBitmap(paddedWidth, h, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(buffer)
            val bitmap = Bitmap.createBitmap(padded, 0, 0, w, h)
            padded.recycle()
            bitmap
        }
    }

    // ------------------------------------------------------------------ plumbing

    private fun helloJson(): String = JSONObject()
        .put("type", "hello")
        .put("sessionId", sessionId)
        .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        .put("sdk", Build.VERSION.SDK_INT)
        .put("app", "remote-control/1.0")
        .toString()

    private fun sendJson(builder: JSONObject.() -> Unit) {
        val text = try {
            JSONObject().apply(builder).toString()
        } catch (e: Exception) {
            return
        }
        val socket = webSocket ?: return
        val queued = socket.send(text)
        if (!queued) RemoteControlState.status.value = "Send failed (outbound queue full)"
    }

    private fun sendError(action: String?, message: String, id: Any? = null) = sendJson {
        put("type", "error")
        if (action != null) put("action", action)
        put("message", message)
        id?.let { put("id", it) }
    }

    private fun shutdown(exitProcess: Boolean) {
        if (shuttingDown) return
        shuttingDown = true
        reconnectJob?.cancel()
        runCatching { webSocket?.close(1000, "shutdown") }
        webSocket = null
        releaseProjection()
        RemoteControlState.isConnected.value = false
        RemoteControlState.status.value = "Stopped"
        if (foregroundStarted) runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
        if (exitProcess) {
            // Give the close frame a moment to flush before the process dies.
            mainHandler.postDelayed({
                android.os.Process.killProcess(android.os.Process.myPid())
            }, 400)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startAsForeground(text: String): Boolean {
        val notification = buildNotification(text)
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
            notifText = text
            foregroundStarted = true
            true
        } catch (e: Exception) {
            try {
                // Pre-Android 10 / degraded path: untyped foreground service.
                startForeground(NOTIF_ID, notification)
                notifText = text
                foregroundStarted = true
                true
            } catch (e2: Exception) {
                RemoteControlState.status.value =
                    "Location permission required for the foreground service"
                false
            }
        }
    }

    private fun updateNotification(text: String) {
        if (!foregroundStarted) return
        notifText = text
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
