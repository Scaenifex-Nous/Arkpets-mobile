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
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.arkpets.mobile.model.CharacterData
import kotlin.math.abs

class PetOverlayService : Service(), SensorEventListener {

    companion object {
        const val EXTRA_CHARACTER_INDEX = "ci"
    }

    private var wm: WindowManager? = null
    private var pet: SpineTextureView? = null
    private var touchView: android.view.View? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var touchCheckRunning = false
    private var sm: SensorManager? = null
    private var physicsThread: Thread? = null
    @Volatile private var running = false

    @Volatile private var gravX = 0f
    @Volatile private var gravY = -9.8f
    @Volatile private var gravityEnabled = true
    @Volatile private var walkSpeed = 350f
    @Volatile private var gravityScale = 1.0f

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("ap", "ArkPets", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) })
        }
        val prefs = getSharedPreferences("arkpets", MODE_PRIVATE)
        gravityEnabled = prefs.getBoolean("gravity", true)
        walkSpeed = prefs.getFloat("walkSpeed", 350f)
        gravityScale = prefs.getFloat("gravityScale", 1.0f)

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1002, NotificationCompat.Builder(this, "ap")
            .setContentTitle("ArkPets").setContentText("Running")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(NotificationCompat.PRIORITY_LOW).setOngoing(true).build())

        val idx = intent?.getIntExtra(EXTRA_CHARACTER_INDEX, -1) ?: -1
        if (idx >= 0 && idx < CharacterData.characters.size) {
            removePet()
            createPet(idx)
        }

        sm?.registerListener(this, sm?.getDefaultSensor(Sensor.TYPE_GRAVITY),
            SensorManager.SENSOR_DELAY_GAME)
        return START_STICKY
    }

    // ---- Pet creation (copies files FIRST, then creates GL view) -----------
    private fun createPet(idx: Int) {
        val asset = CharacterData.getCharacter(idx) ?: return

        // Pre-copy model files to internal storage (do NOT do this on GL thread!)
        try {
            val dir = java.io.File(filesDir, "models/${asset.characterKey}")
            dir.mkdirs()
            val atlasFile = java.io.File(dir, asset.atlasPath.substringAfterLast('/'))
            val skelFile  = java.io.File(dir, asset.skelPath.substringAfterLast('/'))
            val pngFile   = java.io.File(dir, asset.pngPath.substringAfterLast('/'))

            if (!atlasFile.exists()) assets.open(asset.atlasPath).use { it.copyTo(atlasFile.outputStream()) }
            if (!skelFile.exists())  assets.open(asset.skelPath).use { it.copyTo(skelFile.outputStream()) }
            if (!pngFile.exists())   assets.open(asset.pngPath).use { it.copyTo(pngFile.outputStream()) }
            Log.i("ArkPets", "Files copied for ${asset.displayName}")
        } catch (e: Exception) {
            Log.e("ArkPets", "File copy failed: ${e.message}", e)
        }

        // Window 1: Full screen, touch-transparent, renders character
        pet = SpineTextureView(this, asset)
        val dm = resources.displayMetrics
        pet!!.screenW = dm.widthPixels.toFloat()
        pet!!.screenH = dm.heightPixels.toFloat()
        pet!!.windowW = dm.widthPixels; pet!!.windowH = dm.heightPixels
        pet!!.physicsX = dm.widthPixels / 2f
        pet!!.physicsY = dm.heightPixels * 0.5f

        val renderParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm?.addView(pet, renderParams)

        if (getSharedPreferences("arkpets", MODE_PRIVATE).getBoolean("touchEnabled", true)) {
            addTouchWindow()
        }
        startTouchCheck()

        startPhysicsLoop()

    }

    private fun startTouchCheck() {
        if (touchCheckRunning) return
        touchCheckRunning = true
        val runnable = object : Runnable {
            override fun run() {
                if (!touchCheckRunning) return
                val enabled = getSharedPreferences("arkpets", MODE_PRIVATE).getBoolean("touchEnabled", true)
                if (enabled && touchView == null) addTouchWindow()
                else if (!enabled && touchView != null) {
                    try { wm?.removeView(touchView); touchView = null } catch (_: Exception) {}
                }
                handler.postDelayed(this, 500)
            }
        }
        handler.post(runnable)
    }

    private fun addTouchWindow() {
        val tw = 200; val th = 200
        touchView = object : android.view.View(this) {
            var downRawX = 0f; var downRawY = 0f
            var startPhysX = 0f; var startPhysY = 0f
            var dragging = false
            var lx = 0f; var ly = 0f; var lt = 0L; var tvx = 0f; var tvy = 0f

            override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
                when (e.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downRawX = e.rawX; downRawY = e.rawY
                        startPhysX = pet!!.physicsX; startPhysY = pet!!.physicsY
                        lx = e.rawX; ly = e.rawY; lt = System.nanoTime()
                        dragging = false
                        return true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val mx = e.rawX - downRawX; val my = e.rawY - downRawY
                        if (!dragging && (Math.abs(mx) > 8f || Math.abs(my) > 8f)) {
                            dragging = true; pet!!.isDragging = true
                        }
                        if (dragging) {
                            pet!!.physicsX = (startPhysX + mx).coerceIn(100f, pet!!.screenW - 100f)
                            pet!!.physicsY = (startPhysY - my).coerceIn(0f, pet!!.screenH)
                            val now = System.nanoTime()
                            val dt = ((now - lt) / 1e9f).coerceAtLeast(0.001f)
                            tvx = (e.rawX - lx) / dt; tvy = -(e.rawY - ly) / dt
                            lx = e.rawX; ly = e.rawY; lt = now
                        }
                        return true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        if (!dragging) pet!!.playSpecial()
                        else {
                            pet!!.isDragging = false
                            pet!!.flingVx = tvx.coerceIn(-4000f, 4000f)
                            pet!!.flingVy = tvy.coerceIn(-4000f, 4000f)
                        }
                        dragging = false
                        return true
                    }
                }
                return false
            }
        }
        val touchParams = WindowManager.LayoutParams(
            tw, th,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm?.addView(touchView, touchParams)
    }

    private fun removePet() {
        running = false
        touchCheckRunning = false
        physicsThread?.interrupt()
        physicsThread = null
        try { pet?.stopRendering(); wm?.removeView(pet) } catch (_: Exception) {}
        try { wm?.removeView(touchView) } catch (_: Exception) {}
        pet = null; touchView = null
    }

    // ---- Physics loop ------------------------------------------------------
    private fun startPhysicsLoop() {
        running = true
        physicsThread = Thread {
            val p = pet ?: return@Thread
            try { Thread.sleep(500) } catch (_: Exception) {}

            var px = p.screenW / 2f
            var py = p.screenH * 0.5f  // start mid-screen, fall naturally
            var vx = 0f; var vy = 0f
            val baseG = 980f; val drag = 2f; val bounce = 0.3f
            var walking = false; var walkDir = 1f
            var stateTimer = 1f + Math.random().toFloat() * 2f
            var lastNs = System.nanoTime()
            var prefReadTimer = 0f
            var touchWinTimer = 0f

            while (running) {
                val now = System.nanoTime()
                val dt = ((now - lastNs) / 1_000_000_000f).coerceIn(0f, 0.1f)
                lastNs = now

                // Read prefs periodically for instant slider response
                prefReadTimer -= dt
                if (prefReadTimer <= 0f) {
                    val sp = getSharedPreferences("arkpets", MODE_PRIVATE)
                    walkSpeed = sp.getFloat("walkSpeed", 350f)
                    gravityScale = sp.getFloat("gravityScale", 1.0f)
                    gravityEnabled = sp.getBoolean("gravity", true)
                    prefReadTimer = 0.3f
                }
                val sw = p.screenW; val sh = p.screenH
                val onGround = py <= 1f
                val ws = walkSpeed; val gs = gravityScale
                val sensorActive = gravityEnabled && (abs(gravX) > 2f || abs(gravY + 9.8f) > 2f)

                // Skip physics while user is dragging; sync physics state when drag ends
                if (p.isDragging) {
                    try { Thread.sleep(16) } catch (_: Exception) { break }
                    continue
                }
                // After drag ends, sync physics from current position + apply fling velocity
                if (!p.isDragging && (px != p.physicsX || py != p.physicsY)) {
                    px = p.physicsX; py = p.physicsY
                    vx = p.flingVx * 1.5f; vy = p.flingVy * 1.5f
                    p.flingVx = 0f; p.flingVy = 0f
                }

                vy += (-9.8f * baseG * gs) * dt
                if (onGround) {
                    // Auto-walk always runs when on ground
                    stateTimer -= dt
                    if (!walking) {
                        if (stateTimer <= 0f) {
                            walking = true
                            walkDir = if (Math.random() > 0.5f) 1f else -1f
                            stateTimer = 3f + Math.random().toFloat() * 5f
                        }
                    } else {
                        vx += walkDir * ws * dt
                        if (stateTimer <= 0f) {
                            walking = false
                            stateTimer = 1f + Math.random().toFloat() * 3f
                        }
                    }
                    // Sensor jump: tilt up to take off
                    if (sensorActive) {
                        vy += ((-gravY) - (-9.8f)) * baseG * gs * dt
                    }
                } else {
                    // In air: full sensor response
                    if (sensorActive) {
                        vx += (-gravX * baseG * gs) * dt
                        vy += ((-gravY) - (-9.8f)) * baseG * gs * dt
                    }
                }

                vx *= (1f - drag * dt).coerceAtLeast(0f)
                vy *= (1f - drag * dt).coerceAtLeast(0f)
                vx = vx.coerceIn(-500f, 500f); vy = vy.coerceIn(-1200f, 1200f)
                px += vx * dt; py += vy * dt

                // Bounds
                if (py < 0) { py = 0f; if (vy < 0) vy = -vy * (if (sensorActive) 0.2f else 0f) }
                if (py > sh) { py = sh; if (vy > 0) vy = -vy * bounce }
                if (px < 100) { px = 100f; if (vx < 0) { vx = -vx * bounce; walkDir = 1f } }
                if (px > sw - 100) { px = sw - 100f; if (vx > 0) { vx = -vx * bounce; walkDir = -1f } }

                // Auto-walk: lock to ground (unless in zero-g float mode)
                if (!sensorActive && gs > 0.01f && py <= 1f && vy <= 0f) { py = 0f; vy = 0f }

                val fr = vx > 0.5f || (vx > -0.5f && p.facingRight)
                val anim = if (abs(vx) > 10f) "Walk" else "Idle"

                p.physicsX = px; p.physicsY = py
                p.facingRight = fr; p.targetAnimation = anim

                // Move touch window to follow character (~every 200ms)
                touchWinTimer -= dt
                if (touchWinTimer <= 0f) {
                    touchWinTimer = 0.2f
                    // Position window so character body is roughly centered
                    val wx = (px - 100).toInt().coerceIn(0, (sw - 200).toInt())
                    // Center window on character midpoint
                    // Window from feet down — covers lower body, not above head
                    val wy = (sh - py).toInt().coerceIn(0, (sh - 200).toInt())
                    handler.post {
                        try {
                            val tv = touchView ?: return@post
                            val lp = tv.layoutParams as? WindowManager.LayoutParams
                            if (lp != null && (lp.x != wx || lp.y != wy)) {
                                lp.x = wx; lp.y = wy; wm?.updateViewLayout(tv, lp)
                            }
                        } catch (_: Exception) {}
                    }
                }

                try { Thread.sleep(16) } catch (_: Exception) { break }
            }
        }.apply { name = "ArkPets-Physics" }
        physicsThread!!.start()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_GRAVITY) {
            gravityEnabled = getSharedPreferences("arkpets", MODE_PRIVATE).getBoolean("gravity", true)
            if (gravityEnabled) { gravX = event.values[0]; gravY = event.values[1] }
            else { gravX = 0f; gravY = -9.8f }
        }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    override fun onBind(i: Intent?) = null

    override fun onDestroy() {
        sm?.unregisterListener(this)
        removePet()
        super.onDestroy()
    }
}
