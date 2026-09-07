package com.cellcam.app

import android.graphics.SurfaceTexture
import android.view.TextureView
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

class TexturePreviewRenderer(
    private val textureView: TextureView,
    private val eglContext: EglBase.Context
) : VideoSink {

    private val renderer = EglRenderer("CellCamTexturePreview")

    fun init() {
        renderer.init(eglContext, EglBase.CONFIG_RECORDABLE, GlRectDrawer())
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                renderer.createEglSurface(surface)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                renderer.releaseEglSurface(null)
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
        if (textureView.isAvailable) {
            renderer.createEglSurface(textureView.surfaceTexture!!)
        }
    }

    fun setMirror(enabled: Boolean) {
        renderer.setMirror(enabled)
    }

    override fun onFrame(frame: VideoFrame) {
        renderer.onFrame(frame)
    }

    fun release() {
        renderer.release()
    }
}