package com.arkpets.mobile

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.*
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.arkpets.mobile.model.CharacterData
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// =============================================================================
// PetOverlayService — foreground service managing the overlay window, physics,
// touch interaction, and sensor-based gravity response.
//
// Architecture:
//   Window 1 (pet):  MATCH_PARENT, NOT_TOUCHABLE — renders the Spine character
//   Window 2 (touch): 200×200, tracks character — captures drag/tap events
//   Physics thread:  independent 60fps fixed-timestep rigid-body simulation
//   Sensor handler:  TYPE_GRAVITY @ SENSOR_DELAY_GAME
// =============================================================================

class PetOverlayService : Service(), SensorEventListener {

    companion object {
        const val EXTRA_CHARACTER_INDEX = "ci"

        // ---- Preference keys ----
        private const val PREFS_NAME = "arkpets"
        private const val KEY_GRAVITY_ENABLED = "gravity"
        private const val KEY_WALK_SPEED = "walkSpeed"
        private const val KEY_GRAVITY_SCALE = "gravityScale"
        private const val KEY_TOUCH_ENABLED = "touchEnabled"
        private const val KEY_FLIGHT = "flightMode"

        // ---- Physics constants ----
        private const val BASE_GRAVITY = 980f         // px/s² base
        private const val DRAG_COEFFICIENT = 2f        // air resistance
        private const val BOUNCE_WALL = 0.3f            // wall collision restitution
        private const val BOUNCE_CEILING = 0.2f         // ceiling collision restitution (sensor)
        private const val BOUNCE_CEILING_OFF = 0f       // ceiling collision restitution (no sensor)

        private const val MAX_VX = 500f                  // px/s horizontal speed cap
        private const val MAX_VY = 1200f                // px/s vertical speed cap
        private const val MAX_FLING = 4000f             // max fling velocity per axis
        private const val FLING_MULTIPLIER = 1.5f       // drag-release velocity boost
        private const val GROUND_THRESHOLD = 1f         // "on ground" Y threshold

        private const val IDLE_MIN = 1f                 // seconds
        private const val IDLE_MAX = 3f
        private const val WALK_MIN = 3f                 // seconds
        private const val WALK_MAX = 8f
        private const val WALK_SPEED_DEFAULT = 350f
        private const val GRAVITY_SCALE_DEFAULT = 1f

        private const val PHYSICS_DT = 1f / 60f         // 16.67ms fixed timestep
        private const val MAX_FRAME_DT = 0.1f           // clamp to avoid spiral of death
        private const val PREF_READ_INTERVAL = 0.3f     // re-read SharedPreferences
        private const val TOUCH_WIN_INTERVAL = 0.15f    // reposition touch window
        private const val DIAMOND_FPS = 60
        private const val TOUCH_WIN_SIZE = 200          // px, square

        private const val TOUCH_CHECK_INTERVAL = 500L   // ms, poll for touch toggle

        // ---- Sensor ----
        private const val SENSOR_ACTIVATION_THRESHOLD = 2f // m/s² deviation from gravity at rest

        // ---- Other ----
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "ap"
        private const val DRAG_DEADZONE = 8f            // px, before drag activates
        private const val MARGIN = 100f                 // px from screen edge
    }

    // ---- Window management ----
    private var wm: WindowManager? = null
    private var pet: WeakReference<SpineTextureView>? = null
    private var touchView: View? = null
    private val handler = Handler(Looper.getMainLooper())

    // ---- Thread safety ----
    private val running = AtomicBoolean(false)
    private var touchCheckRunning = false

    // ---- Sensor state ----
    private var sm: SensorManager? = null
    @Volatile private var gravX = 0f
    @Volatile private var gravY = -9.8f

    // ---- Cached preferences ----
    @Volatile private var gravityEnabled = true
    @Volatile private var flightEnabled = false
    @Volatile private var walkSpeed = WALK_SPEED_DEFAULT
    @Volatile private var gravityScale = GRAVITY_SCALE_DEFAULT
    @Volatile private var flingGraceTimer = 0f  // ignore sensor briefly after fling

    // ---- Physics thread ----
    private var physicsThread: Thread? = null

    // ========================================================================
    // Service Lifecycle
    // ========================================================================

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        createNotificationChannel()
        loadPreferences()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildForegroundNotification())

        val idx = intent?.getIntExtra(EXTRA_CHARACTER_INDEX, -1) ?: -1
        if (idx >= 0 && idx < CharacterData.characters.size) {
            removePet()
            createPet(idx)
        }

        sm?.registerListener(
            this,
            sm?.getDefaultSensor(Sensor.TYPE_GRAVITY),
            SensorManager.SENSOR_DELAY_GAME
        )
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // User swiped app from recents — keep service running
        // (START_STICKY handles restarts)
        super.onTaskRemoved(rootIntent)
    }

    // ========================================================================
    // Pet creation
    // ========================================================================

    private fun createPet(idx: Int) {
        val asset = CharacterData.getCharacter(idx) ?: return

        // ---- 1. Copy model files to internal storage (NOT on GL thread) ----
        copyModelFiles(asset)

        // ---- 2. Create rendering window (MATCH_PARENT, touch-transparent) ----
        val dm = resources.displayMetrics
        val petView = SpineTextureView(this, asset).apply {
            screenW = dm.widthPixels.toFloat()
            screenH = dm.heightPixels.toFloat()
            windowW = dm.widthPixels
            windowH = dm.heightPixels
            physicsX = dm.widthPixels / 2f
            physicsY = dm.heightPixels * 0.5f
        }
        pet = WeakReference(petView)

        val renderParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            OVERLAY_TYPE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm?.addView(petView, renderParams)

        // ---- 3. Create touch window (if enabled) ----
        if (getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_TOUCH_ENABLED, true)) {
            addTouchWindow(petView)
        }
        startTouchCheck()
        startDiamondLoop()

        // ---- 4. Start physics loop ----
        startPhysicsLoop(petView)
    }

    /** Copy model assets from APK to internal storage (libGDX FileHandle limitation) */
    private fun copyModelFiles(asset: com.arkpets.mobile.model.ModelAsset) {
        try {
            val dir = java.io.File(filesDir, "models/${asset.characterKey}")
            dir.mkdirs()

            fun copyIfMissing(path: String) {
                val dest = java.io.File(dir, path.substringAfterLast('/'))
                if (!dest.exists()) {
                    assets.open(path).use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
                }
            }

            copyIfMissing(asset.atlasPath)
            copyIfMissing(asset.skelPath)
            copyIfMissing(asset.pngPath)

            Log.i("ArkPets", "Files ready for ${asset.displayName}")
        } catch (e: Exception) {
            Log.e("ArkPets", "File copy failed: ${e.message}", e)
        }
    }

    // ========================================================================
    // Touch Window
    // ========================================================================

    private fun addTouchWindow(petView: SpineTextureView) {
        val tw = TOUCH_WIN_SIZE
        val touch = TouchHandlerView(this, petView)
        touchView = touch

        val touchParams = WindowManager.LayoutParams(
            tw, tw,
            OVERLAY_TYPE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm?.addView(touch, touchParams)
    }

    /** Inner class for touch event handling — draws diamond indicator at bottom */
    private inner class TouchHandlerView(
        context: Context,
        private val petView: SpineTextureView
    ) : View(context) {

        private var downRawX = 0f
        private var downRawY = 0f
        private var startPhysX = 0f
        private var startPhysY = 0f
        private var dragging = false
        private var prevX = 0f; private var prevY = 0f
        private var prevTimeNs = 0L
        private var trackVx = 0f; private var trackVy = 0f
        private val diamondPaint = android.graphics.Paint().apply {
            color = 0x60FFFFFF; style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2.5f; isAntiAlias = true
        }
        private val diamondPath = android.graphics.Path()

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            if (!petView.isDragging && petView.physicsY < 80f) {
                val cx = width / 2f; val cy = height / 2f
                val r = minOf(width, height) * 0.15f
                diamondPath.rewind()
                diamondPath.moveTo(cx, cy - r)
                diamondPath.lineTo(cx + r, cy)
                diamondPath.lineTo(cx, cy + r)
                diamondPath.lineTo(cx - r, cy)
                diamondPath.close()
                canvas.drawPath(diamondPath, diamondPaint)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX; downRawY = e.rawY
                    startPhysX = petView.physicsX; startPhysY = petView.physicsY
                    prevX = e.rawX; prevY = e.rawY; prevTimeNs = System.nanoTime()
                    dragging = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX; val dy = e.rawY - downRawY
                    if (!dragging && (abs(dx) > DRAG_DEADZONE || abs(dy) > DRAG_DEADZONE)) {
                        dragging = true; petView.isDragging = true
                    }
                    if (dragging) {
                        petView.physicsX = (startPhysX + dx).coerceIn(MARGIN, petView.screenW - MARGIN)
                        petView.physicsY = (startPhysY - dy).coerceIn(0f, petView.screenH - 210f)
                        val now = System.nanoTime()
                        val dt = ((now - prevTimeNs) / 1e9f).coerceAtLeast(0.001f)
                        trackVx = (e.rawX - prevX) / dt
                        trackVy = -(e.rawY - prevY) / dt
                        prevX = e.rawX; prevY = e.rawY; prevTimeNs = now
                    }
                    // Redraw diamond on move (position changes)
                    invalidate()
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (!dragging) { petView.playSpecial() }
                    else {
                        petView.isDragging = false
                        petView.flingVx = trackVx.coerceIn(-MAX_FLING, MAX_FLING)
                        petView.flingVy = trackVy.coerceIn(-MAX_FLING, MAX_FLING)
                    }
                    dragging = false
                    invalidate()
                    return true
                }
            }
            return false
        }
    }

    /** Invalidate touch view at 60fps for smooth diamond indicator */
    private fun startDiamondLoop() {
        val interval = (1000f / DIAMOND_FPS).toLong()
        val runnable = object : Runnable {
            override fun run() {
                if (!running.get()) return
                touchView?.invalidate()
                handler.postDelayed(this, interval)
            }
        }
        handler.post(runnable)
    }

    /** Poll SharedPreferences for touch toggle changes every ~500ms */
    private fun startTouchCheck() {
        if (touchCheckRunning) return
        touchCheckRunning = true

        val runnable = object : Runnable {
            override fun run() {
                if (!touchCheckRunning) return
                val enabled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getBoolean(KEY_TOUCH_ENABLED, true)
                if (enabled && touchView == null) {
                    pet?.get()?.let { addTouchWindow(it) }
                } else if (!enabled && touchView != null) {
                    try { wm?.removeView(touchView); touchView = null } catch (_: Exception) {}
                }
                handler.postDelayed(this, TOUCH_CHECK_INTERVAL)
            }
        }
        handler.post(runnable)
    }

    // ========================================================================
    // Physics Engine — fixed timestep accumulator
    // ========================================================================

    private fun startPhysicsLoop(petView: SpineTextureView) {
        running.set(true)
        physicsThread = Thread({
            // Wait for GL surface to be ready
            try { Thread.sleep(500) } catch (_: InterruptedException) { return@Thread }

            val p = petView
            var px = p.screenW / 2f
            var py = p.screenH * 0.5f
            var vx = 0f
            var vy = 0f

            var walking = false
            var walkDir = 1f
            var walkDirX = 1f; var walkDirY = 0f
            var stateTimer = IDLE_MIN + Math.random().toFloat() * (IDLE_MAX - IDLE_MIN)

            var accumulator = 0f
            var lastNs = System.nanoTime()
            var prefReadTimer = 0f
            var touchWinTimer = 0f

            while (running.get()) {
                val now = System.nanoTime()
                val rawDt = ((now - lastNs) / 1_000_000_000f).coerceIn(0f, MAX_FRAME_DT)
                lastNs = now
                accumulator += rawDt

                // ---- Read preferences periodically ----
                prefReadTimer -= rawDt
                flingGraceTimer -= rawDt
                if (prefReadTimer <= 0f) {
                    val sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    walkSpeed = sp.getFloat(KEY_WALK_SPEED, WALK_SPEED_DEFAULT)
                    gravityScale = sp.getFloat(KEY_GRAVITY_SCALE, GRAVITY_SCALE_DEFAULT)
                    gravityEnabled = sp.getBoolean(KEY_GRAVITY_ENABLED, true)
                    flightEnabled = sp.getBoolean(KEY_FLIGHT, false)
                    prefReadTimer = PREF_READ_INTERVAL
                }

                // ---- Skip physics while dragging (touch handler sets position directly) ----
                if (p.isDragging) {
                    try { Thread.sleep(16) } catch (_: InterruptedException) { break }
                    lastNs = System.nanoTime()
                    accumulator = 0f
                    continue
                }
                // After drag ends, sync physics from touch position + apply fling velocity
                if (px != p.physicsX || py != p.physicsY) {
                    px = p.physicsX; py = p.physicsY
                    vx = p.flingVx * FLING_MULTIPLIER
                    vy = p.flingVy * FLING_MULTIPLIER
                    p.flingVx = 0f; p.flingVy = 0f
                    flingGraceTimer = 0.5f
                    lastNs = System.nanoTime()
                    accumulator = 0f
                }

                // ---- Fixed timestep physics ----
                while (accumulator >= PHYSICS_DT) {
                    accumulator -= PHYSICS_DT
                    stepPhysics(
                        p, PHYSICS_DT,
                        px, py, vx, vy,
                        walking, walkDir, walkDirX, walkDirY, stateTimer
                    ).also { result ->
                        px = result.px; py = result.py
                        vx = result.vx; vy = result.vy
                        walking = result.walking; walkDir = result.walkDir
                        walkDirX = result.walkDirX; walkDirY = result.walkDirY
                        stateTimer = result.stateTimer
                    }
                }

                // ---- Sync to Spine view (defensive clamp — never let character render off-screen) ----
                val fr = vx > 0.5f || (vx > -0.5f && p.facingRight)
                val anim = if (abs(vx) > 10f) "Walk" else "Idle"
                p.physicsX = px.coerceIn(MARGIN, p.screenW - MARGIN)
                p.physicsY = py.coerceIn(0f, p.screenH)
                p.facingRight = fr; p.targetAnimation = anim

                // ---- Reposition touch window to follow character ----
                touchWinTimer -= rawDt
                if (touchWinTimer <= 0f) {
                    touchWinTimer = TOUCH_WIN_INTERVAL
                    repositionTouchWindow(p, px, py)
                }

                // ---- Sleep for remainder ----
                val frameEnd = System.nanoTime()
                val sleepMs = ((PHYSICS_DT - (frameEnd - lastNs) / 1e9f) * 1000f).toLong()
                if (sleepMs > 1) {
                    try { Thread.sleep(sleepMs) } catch (_: InterruptedException) { break }
                } else {
                    Thread.yield()
                }
            }
        }, "ArkPets-Physics").apply { start() }
    }

    /** Single fixed-timestep physics step. Pure function for testability. */
    private fun stepPhysics(
        p: SpineTextureView,
        dt: Float,
        pxIn: Float, pyIn: Float, vxIn: Float, vyIn: Float,
        walkingIn: Boolean, walkDirIn: Float, walkDirXIn: Float, walkDirYIn: Float, stateTimerIn: Float
    ): PhysicsState {
        var px = pxIn; var py = pyIn
        var vx = vxIn; var vy = vyIn
        var walking = walkingIn
        var walkDir = walkDirIn
        var walkDirX = walkDirXIn; var walkDirY = walkDirYIn
        var stateTimer = stateTimerIn

        val sw = p.screenW; val sh = p.screenH
        val groundLevel = 0f
        val onGround = py <= groundLevel + GROUND_THRESHOLD
        val ws = walkSpeed
        var gs = gravityScale
        val sensorActive = gravityEnabled && !flightEnabled &&
            flingGraceTimer <= 0f &&
            (abs(gravX) > SENSOR_ACTIVATION_THRESHOLD || abs(gravY + 9.8f) > SENSOR_ACTIVATION_THRESHOLD)

        // ---- Flight mode: zero gravity, omnidirectional random walk, no sensor ----
        if (flightEnabled) {
            gs = 0f  // force gravity scale to 0
            stateTimer -= dt
            if (!walking) {
                if (stateTimer <= 0f) {
                    walking = true
                    val angle = Math.random() * Math.PI * 2
                    walkDirX = cos(angle).toFloat()
                    walkDirY = sin(angle).toFloat()
                    stateTimer = 2f + Math.random().toFloat() * 4f
                }
            } else {
                vx += walkDirX * ws * dt
                vy += walkDirY * ws * dt
                if (stateTimer <= 0f) {
                    walking = false
                    stateTimer = 1f + Math.random().toFloat() * 2f
                }
            }
        } else {
            // ---- Normal mode ----
            vy += (-9.8f * BASE_GRAVITY * gs) * dt

            if (onGround) {
                stateTimer -= dt
                if (!walking) {
                    if (stateTimer <= 0f) {
                        walking = true
                        walkDir = if (Math.random() > 0.5f) 1f else -1f
                        walkDirX = walkDir; walkDirY = 0f
                        stateTimer = WALK_MIN + Math.random().toFloat() * (WALK_MAX - WALK_MIN)
                    }
                } else {
                    vx += walkDir * ws * dt
                    if (stateTimer <= 0f) {
                        walking = false
                        stateTimer = IDLE_MIN + Math.random().toFloat() * (IDLE_MAX - IDLE_MIN)
                    }
                }
                if (sensorActive) {
                    vy += ((-gravY) - (-9.8f)) * BASE_GRAVITY * gs * dt
                }
            } else {
                if (sensorActive) {
                    vx += (-gravX * BASE_GRAVITY * gs) * dt
                    vy += ((-gravY) - (-9.8f)) * BASE_GRAVITY * gs * dt
                }
            }
        }

        // ---- Air resistance ----
        vx *= (1f - DRAG_COEFFICIENT * dt).coerceAtLeast(0f)
        vy *= (1f - DRAG_COEFFICIENT * dt).coerceAtLeast(0f)

        // ---- Velocity clamping ----
        vx = vx.coerceIn(-MAX_VX, MAX_VX)
        vy = vy.coerceIn(-MAX_VY, MAX_VY)

        // ---- Integrate position ----
        px += vx * dt
        py += vy * dt

        // ---- Boundary collisions ----
        // Ceiling: hard stop
        val topLimit = sh - 210f
        if (py > topLimit) { py = topLimit; if (vy > 0f) vy = 0f }
        // Floor: soft bounce (ground level adjusts for keyboard)
        if (py < groundLevel) { py = groundLevel; if (vy < 0f) vy = -vy * 0.12f }
        // Horizontal walls: hard stop — no bounce at all
        if (px < MARGIN) { px = MARGIN; if (vx < 0f) { vx = 0f; walkDir = 1f } }
        if (px > sw - MARGIN) { px = sw - MARGIN; if (vx > 0f) { vx = 0f; walkDir = -1f } }

        // ---- Ground lock (respects keyboard height, only in normal mode) ----
        if (!flightEnabled && !sensorActive && gs > 0.01f && py <= groundLevel + GROUND_THRESHOLD && vy <= 0f) {
            py = groundLevel; vy = 0f
        }

        return PhysicsState(px, py, vx, vy, walking, walkDir, walkDirX, walkDirY, stateTimer)
    }

    private data class PhysicsState(
        val px: Float, val py: Float,
        val vx: Float, val vy: Float,
        val walking: Boolean, val walkDir: Float,
        val walkDirX: Float, val walkDirY: Float,
        val stateTimer: Float
    )

    /** Move the touch window — at bottom, position window lower (closer to feet) */
    private fun repositionTouchWindow(p: SpineTextureView, px: Float, py: Float) {
        val sw = p.screenW.toInt()
        val sh = p.screenH.toInt()
        // At bottom: use body midpoint minus 200px offset → window lower on character body
        // Elsewhere: use body midpoint
        val targetY = if (py < sh * 0.35f) {
            py + (p.bodyHeight * 0.35f - 200f).coerceAtLeast(0f)
        } else {
            py + p.bodyHeight * 0.35f
        }
        val wx = (px - 100).toInt().coerceIn(0, sw - TOUCH_WIN_SIZE)
        val wy = (sh - targetY).toInt().coerceIn(0, sh - TOUCH_WIN_SIZE)

        handler.post {
            try {
                val tv = touchView ?: return@post
                val lp = tv.layoutParams as? WindowManager.LayoutParams ?: return@post
                if (lp.x != wx || lp.y != wy) {
                    lp.x = wx; lp.y = wy
                    wm?.updateViewLayout(tv, lp)
                }
                tv.invalidate()  // redraw diamond indicator
            } catch (_: Exception) {
                // View already removed — ignore
            }
        }
    }

    // ========================================================================
    // Cleanup
    // ========================================================================

    private fun removePet() {
        running.set(false)
        touchCheckRunning = false
        physicsThread?.interrupt()
        physicsThread = null

        pet?.get()?.let { p ->
            p.stopRendering()
            try { wm?.removeView(p) } catch (_: Exception) {}
        }
        pet = null

        try { touchView?.let { wm?.removeView(it) } } catch (_: Exception) {}
        touchView = null
    }

    // ========================================================================
    // Sensor
    // ========================================================================

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_GRAVITY) {
            // Re-read gravity enabled state each sensor event for responsiveness
            gravityEnabled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_GRAVITY_ENABLED, true)
            if (gravityEnabled) {
                gravX = event.values[0]
                gravY = event.values[1]
            } else {
                gravX = 0f
                gravY = -9.8f
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        sm?.unregisterListener(this)
        removePet()
        super.onDestroy()
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "ArkPets", NotificationManager.IMPORTANCE_LOW)
                        .apply { setShowBadge(false) }
                )
        }
    }

    private fun buildForegroundNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("ArkPets")
        .setContentText("Running")
        .setSmallIcon(android.R.drawable.ic_menu_view)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .build()

    private fun loadPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        gravityEnabled = prefs.getBoolean(KEY_GRAVITY_ENABLED, true)
        flightEnabled = prefs.getBoolean(KEY_FLIGHT, false)
        walkSpeed = prefs.getFloat(KEY_WALK_SPEED, WALK_SPEED_DEFAULT)
        gravityScale = prefs.getFloat(KEY_GRAVITY_SCALE, GRAVITY_SCALE_DEFAULT)
    }

    /** Resolve the overlay window type based on SDK version */
    private val OVERLAY_TYPE: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
}
