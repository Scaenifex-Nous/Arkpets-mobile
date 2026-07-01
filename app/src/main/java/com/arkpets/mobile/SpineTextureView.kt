package com.arkpets.mobile

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.TextureView
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Graphics
import com.badlogic.gdx.backends.android.AndroidGL20
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.PolygonSpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.graphics.g2d.TextureAtlas.TextureAtlasData
import com.esotericsoftware.spine.*
import com.esotericsoftware.spine.attachments.AtlasAttachmentLoader
import com.arkpets.mobile.model.ModelAsset
import java.io.*

class SpineTextureView(
    context: Context,
    private val asset: ModelAsset
) : TextureView(context), TextureView.SurfaceTextureListener {

    @Volatile var physicsX = 640f
    @Volatile var physicsY = 1260f
    @Volatile var facingRight = true
    @Volatile var targetAnimation = "Idle"
    @Volatile var screenW = 1080f
    @Volatile var screenH = 1920f
    var windowW = 500; var windowH = 700
    @Volatile var isDragging = false
    @Volatile var flingVx = 0f; @Volatile var flingVy = 0f
    @Volatile var bodyHeight = 400f
    @Volatile var footOfs = 200f  // exposed for window alignment

    private var renderThread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var running = false
    @Volatile private var surfaceReady = false
    @Volatile private var cleanedUp = false
    private var surfW = 0; private var surfH = 0

    // EGL
    private var eglDisplay: EGLDisplay? = null
    private var eglSurface: EGLSurface? = null
    private var eglContext: EGLContext? = null
    private var eglConfig: EGLConfig? = null

    // libGDX / Spine
    private var batch: PolygonSpriteBatch? = null
    private var camera: OrthographicCamera? = null
    private var skeletonRenderer: SkeletonRenderer? = null
    private var skeleton: Skeleton? = null
    private var animState: AnimationState? = null
    private var skeletonData: SkeletonData? = null
    private var lastFrameNs = 0L
    private var footOffset = 0f

    init {
        surfaceTextureListener = this
        isOpaque = false
    }

    // ---- SurfaceTextureListener --------------------------------------------
    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        surfW = w; surfH = h
        surfaceReady = true
        renderThread = HandlerThread("SpineTex").apply { start() }
        handler = Handler(renderThread!!.looper)
        handler!!.post { initAndRender(st) }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
        surfW = w; surfH = h
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        stopRendering()
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    // ---- Init & Render Thread ----------------------------------------------
    @Volatile private var renderPhase = "init" // init | loading | ok | error

    private fun initAndRender(st: SurfaceTexture) {
        try {
            glLog("EGL init...")
            initEGL(st)
            glLog("EGL OK, libGDX...")
            initLibGDX()
            glLog("libGDX OK, loading model...")
            loadModel()
            glLog("Model OK, starting render loop")
            renderPhase = "ok"
            running = true
            lastFrameNs = System.nanoTime()
            while (running && surfaceReady) {
                renderFrame()
                EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            }
        } catch (e: Throwable) {
            renderPhase = "error"
            glLog("FAIL: ${e.javaClass.name}: ${e.message}")
            // Draw error color for a few seconds
            try {
                while (running && surfaceReady) {
                    GLES20.glClearColor(1f, 0.2f, 0.2f, 0.7f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                    Thread.sleep(500)
                }
            } catch (_: Exception) {}
        } finally {
            cleanupGL()
        }
    }

    private fun initEGL(st: SurfaceTexture) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
        eglConfig = configs[0]

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)

        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, st, intArrayOf(EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        EGL14.eglSwapInterval(eglDisplay, 0)  // no vsync, max framerate
    }

    private fun initLibGDX() {
        val aGl = AndroidGL20()
        Gdx.gl = aGl
        Gdx.gl20 = aGl
        Gdx.graphics = java.lang.reflect.Proxy.newProxyInstance(
            Graphics::class.java.classLoader,
            arrayOf(Graphics::class.java)
        ) { _, method, args ->
            when {
                method.returnType == Int::class.javaPrimitiveType ->
                    if (method.name.contains("Height") || method.name.contains("Width")) 1920 else 0
                method.returnType == Float::class.javaPrimitiveType -> 0.016f
                method.returnType == Boolean::class.javaPrimitiveType -> false
                method.returnType == Long::class.javaPrimitiveType -> 0L
                else -> null
            }
        } as Graphics
        Gdx.app = java.lang.reflect.Proxy.newProxyInstance(
            com.badlogic.gdx.Application::class.java.classLoader,
            arrayOf(com.badlogic.gdx.Application::class.java)
        ) { _, method, _ ->
            when {
                method.name == "getType" -> com.badlogic.gdx.Application.ApplicationType.Android
                method.returnType == Int::class.javaPrimitiveType -> 0
                method.returnType == Float::class.javaPrimitiveType -> 0f
                method.returnType == Boolean::class.javaPrimitiveType -> false
                method.returnType == Long::class.javaPrimitiveType -> 0L
                else -> null
            }
        } as com.badlogic.gdx.Application

        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        batch = PolygonSpriteBatch()
        skeletonRenderer = SkeletonRenderer()

        camera = OrthographicCamera(surfW.toFloat(), surfH.toFloat()).apply {
            setToOrtho(false, surfW.toFloat(), surfH.toFloat())
            position.set(surfW / 2f, surfH / 2f, 0f)
            update()
        }
    }

    private fun loadModel() {
        renderPhase = "loading"
        val dir = File(context.filesDir, "models/${asset.characterKey}")
        val atlasFile = File(dir, asset.atlasPath.substringAfterLast('/'))
        val skelFile = File(dir, asset.skelPath.substringAfterLast('/'))
        val pngFile = File(dir, asset.pngPath.substringAfterLast('/'))

        // Texture
        val opts = android.graphics.BitmapFactory.Options().apply {
            inScaled = false; inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        val bmp = android.graphics.BitmapFactory.decodeFile(pngFile.absolutePath, opts)
            ?: throw IOException("Bitmap null: ${pngFile.name}")
        val w = bmp.width; val h = bmp.height
        val pixmap = Pixmap(w, h, Pixmap.Format.RGBA8888)
        val argb = IntArray(w * h)
        bmp.getPixels(argb, 0, w, 0, 0, w, h); bmp.recycle()
        val buf = pixmap.pixels; buf.clear()
        for (p in argb) {
            buf.put(((p shr 16) and 0xFF).toByte())
            buf.put(((p shr 8) and 0xFF).toByte())
            buf.put((p and 0xFF).toByte())
            buf.put(((p ushr 24) and 0xFF).toByte())
        }
        buf.rewind()
        val tex = Texture(pixmap); tex.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        pixmap.dispose()

        // Atlas
        val atlasData = TextureAtlasData(FileHandle(atlasFile), FileHandle(pngFile.parentFile), false)
        atlasData.pages.forEach { it.texture = tex }
        val atlas = TextureAtlas(atlasData)

        // Skeleton
        val loader = AtlasAttachmentLoader(atlas)
        val binary = SkeletonBinary(loader); binary.scale = 1.5f * 0.3f
        skeletonData = binary.readSkeletonData(FileHandle(skelFile))
        skeleton = Skeleton(skeletonData).apply { setToSetupPose(); updateWorldTransform() }

        // Calculate Y offset and character height
        var minY = Float.MAX_VALUE; var maxY = Float.MIN_VALUE
        for (i in 0 until skeleton!!.slots.size) {
            val s = skeleton!!.slots[i]
            val att = s.attachment ?: continue
            val verts = FloatArray(32)
            when (att) {
                is com.esotericsoftware.spine.attachments.RegionAttachment -> {
                    att.computeWorldVertices(s.bone, verts, 0, 2)
                    for (j in 0..6 step 2) {
                        if (verts[j+1] < minY) minY = verts[j+1]
                        if (verts[j+1] > maxY) maxY = verts[j+1]
                    }
                }
                is com.esotericsoftware.spine.attachments.MeshAttachment -> {
                    val count = minOf(att.worldVerticesLength, 32)
                    att.computeWorldVertices(s, 0, count, verts, 0, 2)
                    for (j in 0 until minOf(count * 2, 32) step 2) {
                        if (verts[j+1] < minY) minY = verts[j+1]
                        if (verts[j+1] > maxY) maxY = verts[j+1]
                    }
                }
            }
        }
        footOffset = if (minY < Float.MAX_VALUE) -minY else 0f
        bodyHeight = if (minY < Float.MAX_VALUE) maxY - minY else 400f
        footOfs = footOffset  // expose for touch window positioning
        glLog("Bounds: footOffset=${"%.0f".format(footOffset)} height=${"%.0f".format(bodyHeight)}")
        glLog("Bounds: minY=${"%.0f".format(minY)} footOffset=${"%.0f".format(footOffset)}")

        animState = AnimationState(AnimationStateData(skeletonData))
        animState?.addListener(object : AnimationState.AnimationStateAdapter() {
            override fun complete(entry: AnimationState.TrackEntry?) {
                // Special animation ended — resume idle/walk cycle
                if (currentAnimType == AnimType.OTHER && entry?.trackIndex == 0) {
                    currentAnimType = AnimType.IDLE
                }
            }
        })

        // ---- Classify animations (like reference AnimClip.recognizeType) ----
        val animCount = skeletonData!!.animations.size
        val allNames = (0 until animCount).map { skeletonData!!.animations[it].name }
        glLog("Anims(${asset.displayName}): $allNames")

        // Build type-classified lists
        idleAnims.clear(); moveAnims.clear()
        for (i in 0 until animCount) {
            val a = skeletonData!!.animations[i]
            val type = classifyType(a.name)
            when (type) {
                AnimType.IDLE -> idleAnims.add(a)
                AnimType.MOVE -> moveAnims.add(a)
                else -> {} // skip specials, attacks, etc.
            }
        }
        glLog("Classified: ${idleAnims.size} idle, ${moveAnims.size} move")

        // Start with an Idle animation
        val startAnim = if (idleAnims.isNotEmpty()) idleAnims[0]
                        else if (moveAnims.isNotEmpty()) moveAnims[0]
                        else skeletonData!!.animations.firstOrNull()
        if (startAnim != null) {
            animState?.setAnimation(0, startAnim, true)
            currentAnimType = AnimType.IDLE
        }
    }

    // ---- Animation classification (like reference AnimClip.recognizeType) ----
    private val idleAnims = mutableListOf<Animation>()
    private val moveAnims = mutableListOf<Animation>()
    private var currentAnimType = AnimType.IDLE
    private var idleIndex = 0
    private var moveIndex = 0

    enum class AnimType { IDLE, MOVE, OTHER }

    /** Classify animation name → type using reference-style pattern matching */
    private fun classifyType(name: String): AnimType {
        val lower = name.lowercase()
        // Idle patterns: idle, daiji, stand, stop, relax
        if (lower.matches(Regex(".*(?:idle|daiji|stand|stop|relax|idel|dai_ji|breath).*")))
            return AnimType.IDLE
        // Move patterns: move, walk, run
        if (lower.matches(Regex(".*(?:move|walk|run|dush|sprint).*")))
            return AnimType.MOVE
        // If first animation and nothing else matched, treat as IDLE
        return AnimType.OTHER
    }

    /** Get a random idle animation (cycles through available ones) */
    private fun getRandomIdle(): Animation? {
        if (idleAnims.isEmpty()) return skeletonData?.animations?.firstOrNull()
        idleIndex = (idleIndex + 1) % idleAnims.size
        return idleAnims[idleIndex]
    }

    /** Get a random move animation (cycles through available ones) */
    private fun getRandomMove(): Animation? {
        if (moveAnims.isEmpty()) return skeletonData?.animations?.firstOrNull()
        moveIndex = (moveIndex + 1) % moveAnims.size
        return moveAnims[moveIndex]
    }

    /** Pick a random special animation (not idle, not move) and play it once */
    fun playSpecial() {
        val sd = skeletonData ?: return
        val specials = (0 until sd.animations.size).filter { i ->
            val a = sd.animations[i]
            a !in idleAnims && a !in moveAnims
        }.map { sd.animations[it] }
        if (specials.isEmpty()) return
        val pick = specials[(Math.random() * specials.size).toInt()]
        handler?.post {
            animState?.setAnimation(0, pick, false) // play once, don't loop
            currentAnimType = AnimType.OTHER
        }
    }

    private fun renderFrame() {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        if (renderPhase != "ok" || skeleton == null || camera == null || batch == null) {
            // Draw fallback indicator
            val c = when (renderPhase) {
                "init" -> floatArrayOf(0.2f, 0.2f, 0.8f, 0.5f)
                "loading" -> floatArrayOf(0.2f, 0.7f, 0.2f, 0.5f)
                "error" -> floatArrayOf(1f, 0.2f, 0.2f, 0.7f)
                else -> floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f)
            }
            GLES20.glClearColor(c[0], c[1], c[2], c[3])
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            return
        }

        val now = System.nanoTime()
        val dt = ((now - lastFrameNs) / 1_000_000_000f).coerceIn(0f, 0.1f)
        lastFrameNs = now

        val sk = skeleton!!
        val cam = camera!!
        val bat = batch!!

        try {
            // Animation — use classified Idle/Move lists
            val wantType = if (targetAnimation.equals("Walk", ignoreCase = true)) AnimType.MOVE else AnimType.IDLE
            if (wantType != currentAnimType) {
                val found = when (wantType) {
                    AnimType.IDLE -> getRandomIdle()
                    AnimType.MOVE -> getRandomMove()
                    else -> null
                }
                if (found != null) {
                    animState?.setAnimation(0, found, true)
                    currentAnimType = wantType
                }
            }
            animState?.update(dt); animState?.apply(sk)

            // Use footOffset so character feet land at physicsY
            sk.setPosition(physicsX, physicsY + footOffset)
            sk.setScaleX(if (facingRight) 1f else -1f)
            sk.updateWorldTransform()

            cam.update(); bat.projectionMatrix = cam.combined
            bat.begin(); skeletonRenderer?.draw(bat, sk); bat.end()
        } catch (e: Throwable) {
            // Swallow frame errors
        }
    }

    private fun cleanupGL() {
        if (cleanedUp) return
        cleanedUp = true
        running = false
        try { batch?.dispose() } catch (_: Exception) {}
        try {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        } catch (_: Exception) {}
        batch = null; skeleton = null; animState = null; skeletonData = null
        skeletonRenderer = null
    }

    fun stopRendering() {
        running = false
        surfaceReady = false
        handler?.removeCallbacksAndMessages(null)
        renderThread?.quitSafely()
        try { renderThread?.join(3000) } catch (_: Exception) {}
    }

    private fun glLog(msg: String) {
        Log.i("SpineTex", "[${asset.characterKey}] $msg")
        try {
            File(context.filesDir, "arkpets_gl.log").appendText(
                "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())} $msg\n"
            )
        } catch (_: Exception) {}
    }
}
