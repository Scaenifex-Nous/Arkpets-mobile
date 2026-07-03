package com.arkpets.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.esotericsoftware.spine.attachments.MeshAttachment
import com.esotericsoftware.spine.attachments.RegionAttachment
import com.arkpets.mobile.model.ModelAsset
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

// =============================================================================
// SpineTextureView — OpenGL ES 2.0 + libGDX + Spine skeletal animation renderer
//
// Renders a spine character on a TextureView using a dedicated GL thread.
// Communicates with the physics thread via @Volatile fields (physicsX/Y,
// targetAnimation, facingRight). No locks — single-writer, single-reader.
// =============================================================================

class SpineTextureView(
    context: Context,
    private val asset: ModelAsset
) : TextureView(context), TextureView.SurfaceTextureListener {

    // ---- Cross-thread state (physics thread writes, GL thread reads) ----

    @Volatile var physicsX = 640f
    @Volatile var physicsY = 1260f
    @Volatile var facingRight = true
    @Volatile var targetAnimation = "Idle"
    @Volatile var screenW = 1080f
    @Volatile var screenH = 1920f
    @Volatile var isDragging = false
    @Volatile var flingVx = 0f
    @Volatile var flingVy = 0f
    @Volatile var bodyHeight = 400f
    @Volatile var footOfs = 200f

    var windowW = 500
    var windowH = 700

    // ---- Internal state ----

    private var renderThread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var running = false
    @Volatile private var surfaceReady = false
    @Volatile private var cleanedUp = false
    private var surfW = 0
    private var surfH = 0

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

    // ========================================================================
    // SurfaceTextureListener
    // ========================================================================

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        surfW = w; surfH = h
        surfaceReady = true
        renderThread = HandlerThread("SpineTex-${asset.characterKey}").apply { start() }
        handler = Handler(renderThread!!.looper)
        handler!!.post { initAndRender(st) }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
        surfW = w; surfH = h
        // Camera is re-ortho'd from renderFrame scale factors — no explicit resize needed
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        stopRendering()
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    // ========================================================================
    // GL thread entry point
    // ========================================================================

    @Volatile private var renderPhase = "init" // init → loading → ok → error

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
            glLog("FATAL: ${e.javaClass.name}: ${e.message}")
            Log.e("SpineTex", "Render init failed", e)
            // Show error indicator briefly
            try {
                var errorFrames = 0
                while (running && surfaceReady && errorFrames < 180) { // ~3 seconds
                    GLES20.glClearColor(1f, 0.15f, 0.15f, 0.8f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                    Thread.sleep(16)
                    errorFrames++
                }
            } catch (_: Exception) {
                // Already dying
            }
        } finally {
            cleanupGL()
        }
    }

    // ========================================================================
    // EGL Setup
    // ========================================================================

    private fun initEGL(st: SurfaceTexture) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw RuntimeException("eglGetDisplay failed")

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("eglInitialize failed")
        }

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
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)) {
            throw RuntimeException("eglChooseConfig failed")
        }
        eglConfig = configs[0] ?: throw RuntimeException("No EGL config")

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext failed")

        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, st, intArrayOf(EGL14.EGL_NONE), 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreateWindowSurface failed")

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent failed")
        }
        EGL14.eglSwapInterval(eglDisplay, EGL_FRAME_RATE) // uncapped for responsiveness
    }

    // ========================================================================
    // libGDX proxy — minimal interface for spine-libgdx
    // ========================================================================

    private fun initLibGDX() {
        val aGl = AndroidGL20()
        Gdx.gl = aGl
        Gdx.gl20 = aGl

        // Minimal Graphics proxy — spine only reads dimensions & delta time
        Gdx.graphics = java.lang.reflect.Proxy.newProxyInstance(
            Graphics::class.java.classLoader,
            arrayOf(Graphics::class.java)
        ) { _, method, _ ->
            when {
                method.returnType == Int::class.javaPrimitiveType ->
                    if (method.name.contains("Height") || method.name.contains("Width")) REFERENCE_HEIGHT else 0
                method.returnType == Float::class.javaPrimitiveType -> DEFAULT_DELTA
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

    // ========================================================================
    // Model Loading — with SkeletonData cache
    // ========================================================================

    private fun loadModel() {
        renderPhase = "loading"

        val dir = File(context.filesDir, "models/${asset.characterKey}")
        val atlasFile = File(dir, asset.atlasPath.substringAfterLast('/'))
        val skelFile = File(dir, asset.skelPath.substringAfterLast('/'))
        val pngFile = File(dir, asset.pngPath.substringAfterLast('/'))

        // ---- Load texture ----
        val bmp = decodeBitmap(pngFile)
        val tex = bitmapToTexture(bmp)
        // bmp is recycled inside bitmapToTexture

        // ---- Load atlas ----
        val atlasData = TextureAtlasData(FileHandle(atlasFile), FileHandle(pngFile.parentFile), false)
        atlasData.pages.forEach { it.texture = tex }
        val atlas = TextureAtlas(atlasData)

        // ---- Load skeleton binary (fast, < 50ms) ----
        val loader = AtlasAttachmentLoader(atlas)
        val binary = SkeletonBinary(loader).apply { scale = SKELETON_SCALE }
        skeletonData = binary.readSkeletonData(FileHandle(skelFile))

        // ---- Create skeleton instance ----
        skeleton = Skeleton(skeletonData).apply {
            setToSetupPose()
            updateWorldTransform()
        }

        // ---- Compute bounding box & foot offset ----
        val bounds = computeCharacterBounds(skeleton!!)
        footOffset = bounds.first
        bodyHeight = bounds.second
        footOfs = footOffset
        glLog("Bounds: footOffset=${"%.0f".format(footOffset)} height=${"%.0f".format(bodyHeight)}")

        // ---- Animation state ----
        animState = AnimationState(AnimationStateData(skeletonData)).apply {
            addListener(object : AnimationState.AnimationStateAdapter() {
                override fun complete(entry: AnimationState.TrackEntry?) {
                    if (currentAnimType == AnimType.OTHER && entry?.trackIndex == 0) {
                        currentAnimType = AnimType.IDLE
                    }
                }
            })
        }

        // ---- Classify animations (Idle / Move / Other) ----
        classifyAnimations()

        // ---- Start with first idle ----
        val startAnim = idleAnims.firstOrNull()
            ?: moveAnims.firstOrNull()
            ?: skeletonData!!.animations.firstOrNull()
        if (startAnim != null) {
            animState?.setAnimation(0, startAnim, true)
            currentAnimType = AnimType.IDLE
        }
    }

    /** Decode bitmap with ARGB_8888, no scaling */
    private fun decodeBitmap(file: File): Bitmap {
        val opts = BitmapFactory.Options().apply {
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
            ?: throw java.io.IOException("Failed to decode: ${file.name}")
    }

    /** Convert Android ARGB Bitmap → libGDX RGBA Texture. Recycles the bitmap. */
    private fun bitmapToTexture(bmp: Bitmap): Texture {
        val w = bmp.width
        val h = bmp.height
        val pixelCount = w * h
        val argb = IntArray(pixelCount)
        bmp.getPixels(argb, 0, w, 0, 0, w, h)
        bmp.recycle() // free native bitmap memory early

        val pixmap = Pixmap(w, h, Pixmap.Format.RGBA8888)
        val buf = pixmap.pixels
        buf.clear()

        // ARGB (Android) → RGBA (libGDX): shift each channel
        for (p in argb) {
            buf.put(((p shr 16) and 0xFF).toByte()) // R
            buf.put(((p shr 8) and 0xFF).toByte())  // G
            buf.put((p and 0xFF).toByte())           // B
            buf.put(((p ushr 24) and 0xFF).toByte()) // A
        }
        buf.rewind()

        // argb is now eligible for GC — explicitly null to help large heaps
        // (IntArray of 2048x2048 = 16MB; let GC know it's done)

        val tex = Texture(pixmap).apply {
            setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        }
        pixmap.dispose()
        return tex
    }

    /** Compute (footOffset, bodyHeight) from skeleton slots */
    private fun computeCharacterBounds(sk: Skeleton): Pair<Float, Float> {
        var minY = Float.MAX_VALUE
        var maxY = Float.MIN_VALUE
        val verts = FloatArray(32)

        for (i in 0 until sk.slots.size) {
            val slot = sk.slots[i]
            val att = slot.attachment ?: continue
            when (att) {
                is RegionAttachment -> {
                    att.computeWorldVertices(slot.bone, verts, 0, 2)
                    for (j in 0..6 step 2) {
                        val y = verts[j + 1]
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
                is MeshAttachment -> {
                    val count = min(att.worldVerticesLength, VERT_BUFFER_SIZE)
                    att.computeWorldVertices(slot, 0, count, verts, 0, 2)
                    val limit = min(count * 2, VERT_BUFFER_SIZE)
                    for (j in 0 until limit step 2) {
                        val y = verts[j + 1]
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }
        }

        val footOfs = if (minY < Float.MAX_VALUE) -minY else 0f
        val height = if (minY < Float.MAX_VALUE) maxY - minY else 400f
        return footOfs to height
    }

    // ========================================================================
    // Animation classification (Idle / Move / Other)
    // ========================================================================

    private val idleAnims = mutableListOf<Animation>()
    private val moveAnims = mutableListOf<Animation>()
    private var currentAnimType = AnimType.IDLE

    enum class AnimType { IDLE, MOVE, OTHER }

    private fun classifyAnimations() {
        val sd = skeletonData ?: return
        val animCount = sd.animations.size
        val allNames = (0 until animCount).map { sd.animations[it].name.lowercase() }
        glLog("Anims(${asset.displayName}): $allNames")

        idleAnims.clear()
        moveAnims.clear()

        for (i in 0 until animCount) {
            val a = sd.animations[i]
            when (classifyType(a.name)) {
                AnimType.IDLE -> idleAnims.add(a)
                AnimType.MOVE -> moveAnims.add(a)
                else -> { /* special/attack — used by playSpecial() */ }
            }
        }
        glLog("Classified: ${idleAnims.size} idle, ${moveAnims.size} move")
    }

    private fun classifyType(name: String): AnimType {
        val lower = name.lowercase()
        return when {
            lower.matches(IDLE_REGEX) -> AnimType.IDLE
            lower.matches(MOVE_REGEX) -> AnimType.MOVE
            else -> AnimType.OTHER
        }
    }

    // ========================================================================
    // Animation selection — true random, not sequential
    // ========================================================================

    private fun pickRandomIdle(): Animation? {
        if (idleAnims.isEmpty()) return skeletonData?.animations?.firstOrNull()
        return idleAnims[(Math.random() * idleAnims.size).toInt()]
    }

    private fun pickRandomMove(): Animation? {
        if (moveAnims.isEmpty()) return skeletonData?.animations?.firstOrNull()
        return moveAnims[(Math.random() * moveAnims.size).toInt()]
    }

    /** Play a random special animation once (Attack, Skill, Interact, etc.) */
    fun playSpecial() {
        val sd = skeletonData ?: return
        val specials = (0 until sd.animations.size)
            .map { sd.animations[it] }
            .filter { it !in idleAnims && it !in moveAnims }
        if (specials.isEmpty()) return

        val pick = specials[(Math.random() * specials.size).toInt()]
        handler?.post {
            animState?.setAnimation(0, pick, false) // play once, don't loop
            currentAnimType = AnimType.OTHER
        }
    }

    // ========================================================================
    // Render frame
    // ========================================================================

    private fun renderFrame() {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // Show diagnostic color during loading
        if (renderPhase != "ok" || skeleton == null || camera == null || batch == null) {
            val c = when (renderPhase) {
                "init" -> floatArrayOf(0.2f, 0.2f, 0.8f, 0.5f)     // blue
                "loading" -> floatArrayOf(0.2f, 0.7f, 0.2f, 0.5f)   // green
                "error" -> floatArrayOf(1f, 0.15f, 0.15f, 0.8f)     // red
                else -> floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f)        // gray
            }
            GLES20.glClearColor(c[0], c[1], c[2], c[3])
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            return
        }

        val now = System.nanoTime()
        val dt = ((now - lastFrameNs) / 1_000_000_000f).coerceIn(0f, MAX_DELTA)
        lastFrameNs = now

        try {
            // ---- Animation switching ----
            val wantType = if (targetAnimation.equals("Walk", ignoreCase = true))
                AnimType.MOVE else AnimType.IDLE

            if (wantType != currentAnimType && currentAnimType != AnimType.OTHER) {
                val found = when (wantType) {
                    AnimType.IDLE -> pickRandomIdle()
                    AnimType.MOVE -> pickRandomMove()
                    else -> null
                }
                if (found != null) {
                    animState?.setAnimation(0, found, true)
                    currentAnimType = wantType
                }
            }

            // ---- Update & apply ----
            animState?.update(dt)
            animState?.apply(skeleton!!)

            // Position: footOffset aligns skeleton feet to physicsY
            skeleton!!.setPosition(physicsX, physicsY + footOffset)
            skeleton!!.setScaleX(if (facingRight) 1f else -1f)
            skeleton!!.updateWorldTransform()

            // ---- Draw ----
            camera!!.update()
            batch!!.projectionMatrix = camera!!.combined
            batch!!.begin()
            skeletonRenderer?.draw(batch, skeleton)
            batch!!.end()
        } catch (e: Throwable) {
            // Swallow per-frame errors to keep rendering
        }
    }

    // ========================================================================
    // Cleanup
    // ========================================================================

    private fun cleanupGL() {
        if (cleanedUp) return
        cleanedUp = true
        running = false

        // Dispose Spine/libGDX objects on GL thread
        try { batch?.dispose() } catch (_: Exception) {}
        batch = null
        skeleton = null
        animState = null
        skeletonRenderer = null
        // Note: skeletonData is cached externally — don't dispose it

        // Tear down EGL
        try {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface != null) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                eglSurface = null
            }
            if (eglContext != null) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = null
            }
            if (eglDisplay != null) {
                EGL14.eglTerminate(eglDisplay)
                eglDisplay = null
            }
        } catch (_: Exception) {
            // EGL teardown failure is non-critical
        }
    }

    fun stopRendering() {
        running = false
        surfaceReady = false
        handler?.removeCallbacksAndMessages(null)
        renderThread?.quitSafely()
        try {
            renderThread?.join(THREAD_JOIN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        renderThread = null
        handler = null
    }

    // ========================================================================
    // Logging
    // ========================================================================

    private fun glLog(msg: String) {
        Log.i("SpineTex", "[${asset.characterKey}] $msg")
        try {
            File(context.filesDir, LOG_FILE).appendText(
                "${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())} $msg\n"
            )
        } catch (_: Exception) {
            // Log file write is best-effort
        }
    }

    // ========================================================================
    // Constants
    // ========================================================================

    companion object {
        private const val LOG_FILE = "arkpets_gl.log"
        private const val EGL_FRAME_RATE = 0         // 0 = uncapped
        private const val THREAD_JOIN_TIMEOUT_MS = 3000L
        private const val REFERENCE_HEIGHT = 1920    // Fake Gdx.graphics dimensions
        private const val DEFAULT_DELTA = 0.016f     // ~60fps reference delta
        private const val MAX_DELTA = 0.1f           // Clamp to avoid spiral of death
        private const val SKELETON_SCALE = 1.5f * 0.3f // = 0.45
        private const val VERT_BUFFER_SIZE = 32

        // Classification regex patterns (matching ArkPets reference)
        private val IDLE_REGEX = Regex(".*(?:idle|daiji|stand|stop|relax|idel|dai_ji|breath).*")
        private val MOVE_REGEX = Regex(".*(?:move|walk|run|dush|sprint).*")
    }
}


