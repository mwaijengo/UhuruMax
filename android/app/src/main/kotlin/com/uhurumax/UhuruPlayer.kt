package com.uhurumax

import android.content.Context
import android.util.Base64
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.view.TextureRegistry
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** What the server's `/playback` endpoint returns, as sent from Dart. */
data class PlayerSource(
    val url: String,
    val streamType: String,
    val headers: Map<String, String>,
    val drmType: String,
    val licenseUrl: String,
    val licenseHeaders: Map<String, String>,
    val clearKeys: List<Pair<String, String>>,
    val maxHeight: Int,
    val muted: Boolean,
) {
    companion object {
        fun from(args: Map<*, *>): PlayerSource {
            val drm = args["drm"] as? Map<*, *> ?: emptyMap<String, Any>()
            val keys = (drm["clearKeys"] as? List<*>).orEmpty().mapNotNull { item ->
                val map = item as? Map<*, *> ?: return@mapNotNull null
                val kid = map["kid"]?.toString().orEmpty()
                val key = map["key"]?.toString().orEmpty()
                if (kid.isEmpty() || key.isEmpty()) null else kid to key
            }
            return PlayerSource(
                url = args["url"]?.toString().orEmpty(),
                streamType = args["streamType"]?.toString() ?: "auto",
                headers = stringMap(args["headers"]),
                drmType = drm["type"]?.toString() ?: "none",
                licenseUrl = drm["licenseUrl"]?.toString().orEmpty(),
                licenseHeaders = stringMap(drm["licenseHeaders"]),
                clearKeys = keys,
                maxHeight = (args["maxHeight"] as? Number)?.toInt() ?: 0,
                muted = args["muted"] == true,
            )
        }

        private fun stringMap(value: Any?): Map<String, String> =
            (value as? Map<*, *>).orEmpty()
                .mapNotNull { (k, v) -> if (k == null || v == null) null else k.toString() to v.toString() }
                .toMap()
    }
}

/** Method channel `uhurumax/player`; each player streams events on `uhurumax/player/<id>`. */
@OptIn(UnstableApi::class)
class UhuruPlayerPlugin(
    private val context: Context,
    private val messenger: BinaryMessenger,
    private val textures: TextureRegistry,
    private val keepScreenOn: (Boolean) -> Unit,
) : MethodChannel.MethodCallHandler {
    private val players = HashMap<Long, UhuruPlayer>()
    private val channel = MethodChannel(messenger, "uhurumax/player")
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    init {
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "create" -> {
                    val source = PlayerSource.from(call.arguments as Map<*, *>)
                    val producer = textures.createSurfaceProducer()
                    val id = producer.id()
                    val events = EventChannel(messenger, "uhurumax/player/$id")
                    players[id] = UhuruPlayer(context, http, producer, events, source)
                    result.success(id)
                }
                "play" -> withPlayer(call, result) { it.play() }
                "pause" -> withPlayer(call, result) { it.pause() }
                "setMuted" -> withPlayer(call, result) { it.setMuted(call.argument<Boolean>("muted") == true) }
                "setMaxHeight" -> withPlayer(call, result) { it.setMaxHeight(call.argument<Int>("maxHeight") ?: 0) }
                "retry" -> withPlayer(call, result) { it.retry() }
                "keepScreenOn" -> {
                    keepScreenOn(call.argument<Boolean>("on") == true)
                    result.success(null)
                }
                "dispose" -> {
                    players.remove(idOf(call))?.release()
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        } catch (e: Exception) {
            result.error("PLAYER", e.message ?: e.toString(), null)
        }
    }

    private fun idOf(call: MethodCall): Long = (call.argument<Number>("id") ?: -1).toLong()

    private inline fun withPlayer(call: MethodCall, result: MethodChannel.Result, action: (UhuruPlayer) -> Unit) {
        val player = players[idOf(call)]
        if (player == null) {
            result.error("NO_PLAYER", "Player not found", null)
            return
        }
        action(player)
        result.success(null)
    }

    fun dispose() {
        channel.setMethodCallHandler(null)
        players.values.forEach { it.release() }
        players.clear()
    }
}

@OptIn(UnstableApi::class)
class UhuruPlayer(
    context: Context,
    http: OkHttpClient,
    private val producer: TextureRegistry.SurfaceProducer,
    private val events: EventChannel,
    private val source: PlayerSource,
) : Player.Listener, TextureRegistry.SurfaceProducer.Callback, EventChannel.StreamHandler {
    private var sink: EventChannel.EventSink? = null
    private val pending = ArrayList<Map<String, Any?>>()
    private val player: ExoPlayer

    init {
        val streamData = dataSource(http, source.headers)
        val mediaSources = DefaultMediaSourceFactory(context).setDataSourceFactory(streamData)
        drmSessionManager(http)?.let { manager -> mediaSources.setDrmSessionManagerProvider { manager } }

        player = ExoPlayer.Builder(context).setMediaSourceFactory(mediaSources).build()
        player.addListener(this)
        player.volume = if (source.muted) 0f else 1f
        applyMaxHeight(source.maxHeight)

        events.setStreamHandler(this)
        producer.setCallback(this)
        player.setVideoSurface(producer.surface)

        player.setMediaItem(mediaItem())
        player.playWhenReady = true
        player.prepare()
    }

    private fun mediaItem(): MediaItem {
        val builder = MediaItem.Builder().setUri(source.url)
        val path = source.url.substringBefore('?').lowercase()
        val mime = when (source.streamType) {
            "hls" -> MimeTypes.APPLICATION_M3U8
            "dash" -> MimeTypes.APPLICATION_MPD
            "progressive" -> null
            else -> when {
                path.endsWith(".m3u8") || path.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
                path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
                else -> null
            }
        }
        mime?.let { builder.setMimeType(it) }
        return builder.build()
    }

    private fun dataSource(http: OkHttpClient, headers: Map<String, String>): OkHttpDataSource.Factory {
        val factory = OkHttpDataSource.Factory(http)
        val userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
        factory.setUserAgent(userAgent ?: "UhuruMax/1.0 (Linux; Android) ExoPlayer")
        factory.setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) })
        return factory
    }

    private fun drmSessionManager(http: OkHttpClient): DrmSessionManager? {
        val (uuid, callback) = when (source.drmType) {
            "clearkey" -> C.CLEARKEY_UUID to clearKeyCallback(http)
            "widevine" -> C.WIDEVINE_UUID to licenseCallback(http)
            else -> return null
        }
        return DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(uuid, mediaDrmProvider)
            .setMultiSession(false)
            .build(callback)
    }

    private fun clearKeyCallback(http: OkHttpClient): MediaDrmCallback {
        if (source.clearKeys.isEmpty()) return licenseCallback(http)
        val keys = JSONArray()
        source.clearKeys.forEach { (kid, key) ->
            keys.put(JSONObject().put("kty", "oct").put("kid", base64Url(kid)).put("k", base64Url(key)))
        }
        val license = JSONObject().put("keys", keys).put("type", "temporary")
        return LocalMediaDrmCallback(license.toString().toByteArray(Charsets.UTF_8))
    }

    /** License servers often check the same Referer/Origin as the stream. */
    private fun licenseCallback(http: OkHttpClient): MediaDrmCallback {
        val headers = source.headers + source.licenseHeaders
        val callback = HttpMediaDrmCallback(source.licenseUrl, dataSource(http, headers))
        headers.forEach { (name, value) -> callback.setKeyRequestProperty(name, value) }
        return callback
    }

    fun play() {
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    fun pause() = player.pause()

    fun setMuted(muted: Boolean) {
        player.volume = if (muted) 0f else 1f
    }

    fun setMaxHeight(maxHeight: Int) = applyMaxHeight(maxHeight)

    fun retry() {
        player.seekToDefaultPosition()
        player.prepare()
        player.play()
    }

    private fun applyMaxHeight(maxHeight: Int) {
        val params = player.trackSelectionParameters.buildUpon()
        if (maxHeight > 0) params.setMaxVideoSize(Int.MAX_VALUE, maxHeight) else params.clearVideoSizeConstraints()
        player.trackSelectionParameters = params.build()
    }

    fun release() {
        events.setStreamHandler(null)
        sink = null
        player.removeListener(this)
        player.release()
        producer.setCallback(null)
        producer.release()
    }

    // Player.Listener

    override fun onPlaybackStateChanged(playbackState: Int) = sendState()

    override fun onIsPlayingChanged(isPlaying: Boolean) = sendState()

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        if (videoSize.width == 0 || videoSize.height == 0) return
        val width = (videoSize.width * videoSize.pixelWidthHeightRatio).toInt()
        producer.setSize(videoSize.width, videoSize.height)
        player.setVideoSurface(producer.surface)
        send(mapOf("event" to "size", "width" to width, "height" to videoSize.height))
    }

    override fun onPlayerError(error: PlaybackException) {
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            retry()
            return
        }
        send(
            mapOf(
                "event" to "error",
                "code" to error.errorCodeName,
                "drm" to (error.errorCode in 6000..6999),
                "message" to (error.cause?.message ?: error.message ?: error.errorCodeName),
            ),
        )
    }

    private fun sendState() {
        val state = when (player.playbackState) {
            Player.STATE_BUFFERING -> "buffering"
            Player.STATE_READY -> "ready"
            Player.STATE_ENDED -> "ended"
            else -> "idle"
        }
        send(mapOf("event" to "state", "state" to state, "playing" to player.isPlaying))
    }

    // SurfaceProducer.Callback: the surface is torn down when the app is backgrounded.

    override fun onSurfaceAvailable() {
        player.setVideoSurface(producer.surface)
    }

    override fun onSurfaceCleanup() {
        player.setVideoSurface(null)
    }

    // EventChannel.StreamHandler

    override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
        sink = events
        pending.forEach { events.success(it) }
        pending.clear()
    }

    override fun onCancel(arguments: Any?) {
        sink = null
    }

    private fun send(event: Map<String, Any?>) {
        val target = sink
        if (target == null) pending.add(event) else target.success(event)
    }

    companion object {
        /** Widevine L1 needs a secure surface, which a Flutter texture is not. */
        private val mediaDrmProvider = ExoMediaDrm.Provider { uuid ->
            val drm = FrameworkMediaDrm.newInstance(uuid)
            if (uuid == C.WIDEVINE_UUID) {
                runCatching { drm.setPropertyString("securityLevel", "L3") }
            }
            drm
        }

        private val hex = Regex("^[0-9a-fA-F]{32}$")

        /** ClearKey wants base64url without padding; the admin may enter hex. */
        fun base64Url(value: String): String {
            val trimmed = value.trim()
            val bytes = if (hex.matches(trimmed)) {
                ByteArray(16) { i -> trimmed.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
            } else {
                return trimmed.replace('+', '-').replace('/', '_').trimEnd('=')
            }
            return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        }
    }
}
