package com.krzys.hardwareinfo

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20

/**
 * Queries the GPU renderer/vendor strings by spinning up a tiny
 * off-screen OpenGL ES context, reading glGetString, and tearing it
 * all down again. Pure framework EGL/GLES calls.
 */
object GpuProbe {

    data class Info(val vendor: String, val renderer: String, val version: String)

    fun query(): Info? {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return null
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) return null
        try {
            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0)
                || numConfigs[0] < 1
            ) return null
            val config = configs[0] ?: return null

            val surface = EGL14.eglCreatePbufferSurface(
                display, config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
            )
            if (surface == EGL14.EGL_NO_SURFACE) return null

            val context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            if (context == EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroySurface(display, surface)
                return null
            }

            return try {
                if (!EGL14.eglMakeCurrent(display, surface, surface, context)) null
                else Info(
                    vendor = GLES20.glGetString(GLES20.GL_VENDOR) ?: "unknown",
                    renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "unknown",
                    version = GLES20.glGetString(GLES20.GL_VERSION) ?: "unknown"
                )
            } finally {
                EGL14.eglMakeCurrent(
                    display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
                EGL14.eglDestroyContext(display, context)
                EGL14.eglDestroySurface(display, surface)
            }
        } finally {
            EGL14.eglTerminate(display)
        }
    }
}
