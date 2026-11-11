package com.tvinpick.lighthome

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

/** Видео-фон: без звука, по кругу, с обрезкой по центру. Играет только пока оболочка на экране. */
private class VideoPlayer(private val file: File) : TextureView.SurfaceTextureListener {
    var view: TextureView? = null
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var prepared = false
    var playing = false
        set(value) {
            field = value
            val p = player ?: return
            if (!prepared) return
            if (value && !p.isPlaying) p.start() else if (!value && p.isPlaying) p.pause()
        }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        val s = Surface(st)
        surface = s
        val p = MediaPlayer()
        player = p
        runCatching {
            p.setDataSource(file.path)
            p.setSurface(s)
            p.isLooping = true
            p.setVolume(0f, 0f)
            p.setOnPreparedListener {
                prepared = true
                fit(it.videoWidth, it.videoHeight)
                if (playing) it.start()
            }
            p.setOnVideoSizeChangedListener { _, w, h -> fit(w, h) }
            p.prepareAsync()
        }
    }

    private fun fit(videoW: Int, videoH: Int) {
        val v = view ?: return
        if (videoW <= 0 || videoH <= 0 || v.width <= 0 || v.height <= 0) return
        val viewW = v.width.toFloat()
        val viewH = v.height.toFloat()
        val k = maxOf(viewW / videoW, viewH / videoH)
        val sx = videoW * k / viewW
        val sy = videoH * k / viewH
        v.setTransform(Matrix().apply { setScale(sx, sy, viewW / 2, viewH / 2) })
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        player?.takeIf { prepared }?.let { fit(it.videoWidth, it.videoHeight) }
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        release()
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit

    fun release() {
        player?.release()
        player = null
        prepared = false
        surface?.release()
        surface = null
    }
}

@Composable
fun VideoBackground(file: File, version: Long, playing: Boolean) {
    val player = remember(version) { VideoPlayer(file) }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player, playing) { player.playing = playing }
    key(version) {
        AndroidView(
        factory = { ctx ->
            TextureView(ctx).also {
                player.view = it
                it.surfaceTextureListener = player
            }
        },
        update = { player.view = it; it.surfaceTextureListener = player },
        modifier = Modifier.fillMaxSize(),
        )
    }
}
