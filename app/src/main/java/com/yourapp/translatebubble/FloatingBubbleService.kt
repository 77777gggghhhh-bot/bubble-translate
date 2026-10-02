package com.yourapp.translatebubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.abs

class FloatingBubbleService : Service() {

    companion object {
        private const val CHANNEL_ID = "translate_bubble_channel"
        private const val NOTIF_ID = 1001
        private const val CLICK_DRAG_THRESHOLD = 12
        private const val LONG_PRESS_MS = 600L
        private const val DOUBLE_TAP_MS = 300L
        private const val MAX_BLOCKS = 80
        private const val PREFS_NAME = "bubble_prefs"
        private const val PREF_X = "bubble_x"
        private const val PREF_Y = "bubble_y"
        private const val PREF_TEXT_SIZE_LEVEL = "text_size_level" // 0=small,1=medium,2=large
        private const val PREF_LANGUAGE_MODE = "language_mode"     // 0=auto,1=force ar->en,2=force en->ar
        const val ACTION_STOP = "com.yourapp.translatebubble.ACTION_STOP"
        const val ACTION_HIDE_OVERLAY = "com.yourapp.translatebubble.ACTION_HIDE_OVERLAY"

        // Bubble colors for each state, so the user can tell what's happening
        // just by looking at the bubble (no need to read a toast).
        private const val COLOR_IDLE = "#3F51B5"       // blue: ready, nothing translated yet
        private const val COLOR_TRANSLATING = "#FFA000" // orange: working right now
        private const val COLOR_ACTIVE = "#43A047"      // green: translation is showing on screen
    }

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams

    // Each translated block is its OWN small overlay window positioned
    // exactly over the original text, so it can be dragged independently.
    private val translatedLabelViews = mutableListOf<Pair<View, WindowManager.LayoutParams>>()

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private val translatorHelper = TranslatorHelper()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(VibratorManager::class.java)
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private var overlayIsShowing = false
    private var isTranslating = false

    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_STOP -> stopSelf()
                ACTION_HIDE_OVERLAY -> {
                    removeWordOverlay()
                    setBubbleColor(COLOR_IDLE)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundWithNotification()
        addBubble()
        maybeRequestIgnoreBatteryOptimizations()
        tts = TextToSpeech(this) { }

        val filter = IntentFilter().apply {
            addAction(ACTION_STOP)
            addAction(ACTION_HIDE_OVERLAY)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(actionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(actionReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(actionReceiver) }
        removeWordOverlay()
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        translatorHelper.close()
        tts?.stop()
        tts?.shutdown()
        serviceJob.cancel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_HIDE_OVERLAY -> {
                removeWordOverlay()
                setBubbleColor(COLOR_IDLE)
            }
        }
        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Translation Bubble",
                NotificationManager.IMPORTANCE_MIN
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val stopIntent = Intent(ACTION_STOP).setPackage(packageName)
        val stopPendingIntent = PendingIntent.getBroadcast(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val hideIntent = Intent(ACTION_HIDE_OVERLAY).setPackage(packageName)
        val hidePendingIntent = PendingIntent.getBroadcast(
            this, 1, hideIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Translate Bubble is running")
            .setContentText("Tap bubble to translate; tap again to hide. Stop closes the bubble.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Hide", hidePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()

        startForeground(NOTIF_ID, notification)
    }

    // ---------------------------------------------------------------------
    // Ask the system not to kill this service to save battery. Without
    // this, aggressive battery managers can silently stop the bubble in
    // the background. Requires the REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
    // permission in the manifest. Safe to call repeatedly - it's a no-op
    // once granted.
    // ---------------------------------------------------------------------
    private fun maybeRequestIgnoreBatteryOptimizations() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { startActivity(intent) }
        }
    }

    // ---------------------------------------------------------------------
    // Haptics: one short tick so the user feels the tap was registered.
    // ---------------------------------------------------------------------
    private fun vibrateTick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(35)
        }
    }

    private fun setBubbleColor(hex: String) {
        (bubbleView as? ImageView)?.setBackgroundColor(Color.parseColor(hex))
    }

    // ---------------------------------------------------------------------
    // Remember where the bubble was left, so it reopens in the same spot
    // instead of resetting to the top every time.
    // ---------------------------------------------------------------------
    private fun savedBubblePosition(): Pair<Int, Int> {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getInt(PREF_X, 0) to prefs.getInt(PREF_Y, 300)
    }

    private fun saveBubblePosition(x: Int, y: Int) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putInt(PREF_X, x)
            .putInt(PREF_Y, y)
            .apply()
    }

    private fun addBubble() {
        val bubble = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_send)
            setBackgroundColor(Color.parseColor(COLOR_IDLE))
            setPadding(24, 24, 24, 24)
        }
        bubbleView = bubble

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val (savedX, savedY) = savedBubblePosition()
        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }

        windowManager.addView(bubbleView, bubbleParams)
        attachDragClickAndLongPressListener(bubble)
    }

    private fun attachDragClickAndLongPressListener(bubble: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var longPressTriggered = false
        var wasDragged = false
        val longPressRunnable = Runnable {
            longPressTriggered = true
            onLongPressStop()
        }

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = bubbleParams.x
                    initialY = bubbleParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    longPressTriggered = false
                    wasDragged = false
                    mainHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > CLICK_DRAG_THRESHOLD || abs(dy) > CLICK_DRAG_THRESHOLD) {
                        mainHandler.removeCallbacks(longPressRunnable)
                        wasDragged = true
                    }
                    bubbleParams.x = initialX + dx
                    bubbleParams.y = initialY + dy
                    windowManager.updateViewLayout(bubbleView, bubbleParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (!longPressTriggered) {
                        val dx = abs(event.rawX - initialTouchX)
                        val dy = abs(event.rawY - initialTouchY)
                        val isTap = dx < CLICK_DRAG_THRESHOLD && dy < CLICK_DRAG_THRESHOLD
                        if (isTap) {
                            vibrateTick() // feel the tap immediately, before any work happens
                            onBubbleClicked()
                        } else if (wasDragged) {
                            snapBubbleToNearestEdge()
                        }
                    }
                    saveBubblePosition(bubbleParams.x, bubbleParams.y)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    true
                }
                else -> false
            }
        }
    }

    // Snap the bubble to whichever screen edge (left/right) it's closer to,
    // like Messenger's chat heads.
    private fun snapBubbleToNearestEdge() {
        val screenWidth = resources.displayMetrics.widthPixels
        val bubbleWidth = bubbleView?.width?.takeIf { it > 0 } ?: dpToPx(56)
        val bubbleCenter = bubbleParams.x + bubbleWidth / 2
        bubbleParams.x = if (bubbleCenter < screenWidth / 2) {
            0
        } else {
            screenWidth - bubbleWidth
        }
        runCatching { windowManager.updateViewLayout(bubbleView, bubbleParams) }
    }

    // ---------------------------------------------------------------------
    // Blend-in backgrounds: capture the screen once per translate, then
    // sample the real pixel color behind each text block instead of using
    // a flat white box. This is what makes the translation look like it
    // replaced the original word in place (Google Lens-style) rather than
    // sitting in an obvious rectangle.
    // ---------------------------------------------------------------------
    private suspend fun captureScreenshotOrNull(
        accessibilityService: TranslationAccessibilityService
    ): Bitmap? = suspendCancellableCoroutine { cont ->
        accessibilityService.captureScreenshot { bitmap ->
            if (cont.isActive) cont.resume(bitmap)
        }
    }

    // ---------------------------------------------------------------------
    // OCR on the captured screenshot: this is what lets the app translate
    // text baked into images and video frames (memes, screenshots inside
    // an app, etc.) - Accessibility only ever sees text that's a real UI
    // element/label, never pixels drawn inside an image. Latin-script only
    // (ML Kit has no Arabic OCR model), which covers the common case of
    // English meme text needing translation.
    // ---------------------------------------------------------------------
    // The system status bar (clock, battery, signal/network icons) always
    // ends up in the raw screenshot and ML Kit will happily try to "read"
    // those tiny icons as text, producing pure garbage ("O24,0 | KB/S
    // LTED I 45" from the battery/network indicators - confirmed straight
    // from a real debug log) that then pollutes a real block it happens to
    // sit near. It is never useful content, so it's cropped out before OCR
    // ever sees it rather than trying to filter the garbage after the fact.
    private fun statusBarHeightPx(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else dpToPx(24)
    }

    private suspend fun runOcr(bitmap: Bitmap): List<ScreenTextBlock> =
        suspendCancellableCoroutine { cont ->
            try {
                val cropTop = statusBarHeightPx().coerceIn(0, bitmap.height - 1)
                val ocrBitmap = if (cropTop > 0) {
                    Bitmap.createBitmap(bitmap, 0, cropTop, bitmap.width, bitmap.height - cropTop)
                } else {
                    bitmap
                }
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                val image = InputImage.fromBitmap(ocrBitmap, 0)
                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        val blocks = mutableListOf<ScreenTextBlock>()
                        for (block in visionText.textBlocks) {
                            val text = normalizeOcrText(block.text)
                            val bounds = block.boundingBox
                            if (text.isNotEmpty() && bounds != null && !bounds.isEmpty) {
                                // Shift bounds back down by the cropped
                                // amount so they still line up with the
                                // real, uncropped screen when the overlay
                                // is placed.
                                bounds.offset(0, cropTop)
                                blocks.add(ScreenTextBlock(text, bounds))
                            }
                        }
                        if (cont.isActive) cont.resume(blocks)
                    }
                    .addOnFailureListener {
                        if (cont.isActive) cont.resume(emptyList())
                    }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(emptyList())
            }
        }

    // Skip blocks that are just a bare number (like counts, "572" or "20K"),
    // or a number directly glued to a short word with no real space (stat
    // displays like "1.6Mfollowers", "536following", "1,553posts") - these
    // come out as garbage when translated as one token since they're not
    // actually a sentence, just a UI stat readout.
    private fun isLikelyJunkNumber(text: String): Boolean {
        val cleaned = text.trim()
        if (cleaned.matches(Regex("^[0-9\u0660-\u0669.,٬]+[KkMmبمألف]?$"))) return true
        return cleaned.matches(Regex("^[0-9\u0660-\u0669.,٬]+\\s*[KkMm]?\\s*[A-Za-z\u0600-\u06FF]{2,15}$"))
    }

    // OCR sometimes returns a block's text with internal line breaks
    // (e.g. a two-line stat like "1.6M\nfollowers"). Translating text with
    // embedded newlines can confuse the model or glue words together with
    // no space in the result - flatten to single-spaced text first.
    private fun normalizeOcrText(text: String): String =
        text.replace(Regex("\\s+"), " ").trim()

    // Skip ONLY genuinely icon-button-sized elements (a toolbar icon's own
    // tiny label, like "Edit" or "New" squeezed under/next to a 24-48dp
    // icon). The previous thresholds (64dp both dimensions) were wrong:
    // almost ANY single line of normal body text is under 64dp tall
    // regardless of content, so legitimate short words (not just UI
    // chrome) were being silently dropped - this is the bug behind
    // short real words refusing to translate. A true icon label is small
    // in BOTH dimensions at a much tighter bound (actual icon-button
    // size), not just "one short line of text".
    private fun isLikelyIconChrome(block: ScreenTextBlock): Boolean {
        val text = block.text.trim()
        val w = block.bounds.width()
        val h = block.bounds.height()
        return text.length <= 12 && w < dpToPx(40) && h < dpToPx(40)
    }

    // ---------------------------------------------------------------------
    // Common UI action phrases from social apps (Instagram, TikTok, etc.)
    // that showed up repeatedly as noise in real debug logs: "Reply",
    // "See translation" (the app's OWN translate button - translating it
    // is absurd), "Double tap to play/like", "Go to profile", "Profile
    // picture of X", "Follow", "Menu", "New chat"... These are navigation
    // controls, not content, and matching them by exact/contains phrase
    // (not just size) is what actually cleans up comment sections, since
    // several of them (avatar rows, "Double tap to play") aren't
    // icon-sized.
    // ---------------------------------------------------------------------
    private val genericUiPhrases = listOf(
        "reply", "see translation", "like", "follow", "following", "message",
        "share", "copy", "edit", "new chat", "menu", "write", "go to profile",
        "view profile", "profile picture", "more options", "send", "post",
        "comment", "view likes", "double tap to like", "double tap to play",
        "create a reel", "reels", "friends", "join the conversation",
        "add a comment"
    )

    // Dynamic UI labels that embed a username/name in the middle, so a
    // fixed phrase list can never match them (e.g. "Go to Omikko12's
    // profile", "View Omikko12's story") - seen verbatim in real debug
    // logs leaking into translated output. Matched structurally instead
    // of by exact text.
    private val genericUiPatterns = listOf(
        Regex("^go to .+'s profile$", RegexOption.IGNORE_CASE),
        Regex("^view .+'s story$", RegexOption.IGNORE_CASE),
        Regex("^.+'s profile picture$", RegexOption.IGNORE_CASE)
    )

    private fun isLikelyGenericUiPhrase(text: String): Boolean {
        val cleaned = text.trim().lowercase()
        if (cleaned.isEmpty() || cleaned.length > 60) return false
        if (genericUiPatterns.any { it.matches(cleaned) }) return true
        if (cleaned.length > 40) return false
        return genericUiPhrases.any { phrase ->
            cleaned == phrase ||
                // allow a short trailing bit (a count, a separator dot) but
                // require the phrase to be essentially the whole block, so
                // real sentences that merely contain a common word like
                // "like" or "post" are never touched
                (cleaned.startsWith(phrase) && cleaned.length <= phrase.length + 10)
        }
    }

    // Counts real words (whitespace-separated tokens) rather than raw
    // characters - works the same for Arabic and Latin script, unlike
    // relying on ML Kit's internal line/element split which isn't exposed
    // on ScreenTextBlock (shared between OCR and Accessibility sources).
    private fun wordCount(text: String): Int =
        text.trim().split(Regex("\\s+")).count { it.isNotBlank() }

    // ---------------------------------------------------------------------
    // General-purpose UI-label filter: catches navigation chrome that
    // ISN'T in genericUiPhrases (any app, any language), instead of only
    // ever growing that hand-written list. The rule is a property of the
    // text, not its content: a single word in an icon-sized box is almost
    // always a button label ("إرسال", "Compartir", "共有") - real sentences
    // are virtually never both one token AND rendered that small.
    // genericUiPhrases stays as a cheap fast-path for well-known phrases
    // that AREN'T icon-sized (e.g. "Double tap to like" spans a wider row).
    // ---------------------------------------------------------------------
    private fun isLikelyUiLabel(block: ScreenTextBlock): Boolean {
        val text = block.text.trim()
        if (text.isEmpty()) return false
        if (wordCount(text) >= 2) return false
        if (text.length <= 12 && isLikelyIconChrome(block)) return true
        return isLikelyGenericUiPhrase(text)
    }

    // ---------------------------------------------------------------------
    // Accessibility often exposes one paragraph as several small text
    // nodes (one per line, or per sentence). Translating each separately
    // is what made a single paragraph turn into a scatter of tiny
    // disconnected boxes instead of one clean flowing block like Google
    // Lens shows. This merges vertically-stacked, left-aligned blocks back
    // into one block before translation.
    // ---------------------------------------------------------------------
    // ---------------------------------------------------------------------
    // General spatial merge, replacing the old mergeAdjacentLines (which
    // only ever merged Accessibility blocks, vertically, with matching
    // left edges, BEFORE combining with OCR). This runs on the final
    // combined OCR+Accessibility list, in any direction, so a sentence
    // split between the two sources - or wrapped mid-word, or broken into
    // side-by-side fragments on the same line - gets glued back into one
    // block the way Google Lens groups a paragraph, instead of staying
    // scattered. Union-Find clustering: two blocks are "the same
    // paragraph" if their boxes (slightly expanded) touch or overlap.
    // ---------------------------------------------------------------------
    private fun mergeNearbyBlocks(blocks: List<ScreenTextBlock>): List<ScreenTextBlock> {
        if (blocks.size <= 1) return blocks

        val vGap = dpToPx(6)
        val hGap = dpToPx(14)
        val parent = IntArray(blocks.size) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != c) { val next = parent[c]; parent[c] = r; c = next }
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }

        // Two explicit, directional cases instead of one uniform "expand
        // every box a bit and see what touches" rule - that uniform rule
        // is what let an unrelated icon row chain transitively into a
        // paragraph two messages ago. Both real cases below require
        // genuine overlap along one axis, which a stray nearby icon
        // essentially never has:
        fun overlapLen(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Int =
            minOf(aEnd, bEnd) - maxOf(aStart, bStart)

        fun shouldMerge(a: android.graphics.Rect, b: android.graphics.Rect): Boolean {
            // Case 1 - same line, fragmented: the two boxes occupy
            // basically the same vertical band (one word next to
            // another, or one word OCR'd as two pieces) and are
            // horizontally close.
            val vOverlap = overlapLen(a.top, a.bottom, b.top, b.bottom)
            val minHeight = minOf(a.height(), b.height())
            val sameLine = minHeight > 0 && vOverlap > minHeight / 2
            val hGapBetween = maxOf(a.left, b.left) - minOf(a.right, b.right)
            if (sameLine && hGapBetween <= hGap) return true

            // Case 2 - stacked paragraph lines: meaningfully overlapping
            // horizontal span (same text column - this is what a real
            // wrapped paragraph looks like) and a small vertical gap. An
            // icon row or a scattered chip is essentially never aligned
            // to the SAME horizontal column as unrelated text above or
            // below it, so this stays safe without needing a size cap to
            // do the real work.
            val hOverlap = overlapLen(a.left, a.right, b.left, b.right)
            val minWidth = minOf(a.width(), b.width())
            val sameColumn = minWidth > 0 && hOverlap > minWidth * 2 / 5
            val vGapBetween = maxOf(a.top, b.top) - minOf(a.bottom, b.bottom)
            if (sameColumn && vGapBetween in 0..vGap) return true

            return false
        }

        for (i in blocks.indices) {
            for (j in i + 1 until blocks.size) {
                if (shouldMerge(blocks[i].bounds, blocks[j].bounds)) {
                    union(i, j)
                }
            }
        }

        // Now a secondary safety net rather than the primary defense (the
        // two directional checks above do the real work): a real, even
        // very long, paragraph legitimately needs to merge across dozens
        // of lines (the ChatGPT test paragraph alone is 300dp+ tall), so
        // this is sized generously and is here only to stop a genuinely
        // pathological chain, not ordinary long paragraphs.
        val maxMergedHeight = dpToPx(700)
        val maxMergedWidth = dpToPx(420)

        val clusters = blocks.indices.groupBy { find(it) }
        return clusters.values.flatMap { indices ->
            if (indices.size == 1) {
                return@flatMap listOf(blocks[indices.first()])
            }
            val union = android.graphics.Rect(blocks[indices.first()].bounds)
            indices.drop(1).forEach { union.union(blocks[it].bounds) }

            if (union.height() > maxMergedHeight || union.width() > maxMergedWidth) {
                // Too big to be one real paragraph - fall back to the
                // original, unmerged blocks rather than one oversized,
                // garbled cluster.
                indices.map { blocks[it] }
            } else {
                // Reading order within a cluster: top-to-bottom, then
                // right-to-left for Arabic-leaning content vs left-to-right
                // - approximate with top then left, which reads correctly
                // for both since RTL text itself still renders
                // right-aligned within its own line.
                val ordered = indices.sortedWith(compareBy({ blocks[it].bounds.top }, { blocks[it].bounds.left }))
                val text = ordered.joinToString(" ") { blocks[it].text }
                listOf(ScreenTextBlock(text, union))
            }
        }
    }

    private fun sampleBackgroundColor(bitmap: Bitmap?, bounds: android.graphics.Rect): Int {
        if (bitmap == null) return Color.WHITE
        val left = bounds.left.coerceIn(0, bitmap.width - 1)
        val top = bounds.top.coerceIn(0, bitmap.height - 1)
        val right = bounds.right.coerceIn(left + 1, bitmap.width)
        val bottom = bounds.bottom.coerceIn(top + 1, bitmap.height)

        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var count = 0
        // Sample the edge pixels of the block (the surrounding background),
        // stepping a few pixels at a time for speed - we don't need every
        // pixel, just a good average.
        val step = 4
        var x = left
        while (x < right) {
            rSum += Color.red(bitmap.getPixel(x, top)); gSum += Color.green(bitmap.getPixel(x, top)); bSum += Color.blue(bitmap.getPixel(x, top)); count++
            x += step
        }
        if (count == 0) return Color.WHITE
        return Color.rgb((rSum / count).toInt(), (gSum / count).toInt(), (bSum / count).toInt())
    }

    // Pick black or white text for readable contrast against a sampled
    // background color (standard relative-luminance check).
    private fun readableTextColorFor(backgroundColor: Int): Int {
        val luminance = (0.299 * Color.red(backgroundColor) +
            0.587 * Color.green(backgroundColor) +
            0.114 * Color.blue(backgroundColor)) / 255
        return if (luminance > 0.6) Color.BLACK else Color.WHITE
    }

    private fun onLongPressStop() {
        removeWordOverlay()
        stopSelf()
    }

    // ---------------------------------------------------------------------
    // Preferences set from MainActivity: text size level and language mode.
    // ---------------------------------------------------------------------
    private fun textSizeScale(): Float {
        val level = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(PREF_TEXT_SIZE_LEVEL, 1)
        return when (level) {
            0 -> 0.85f  // small
            2 -> 1.3f   // large
            else -> 1f  // medium (default)
        }
    }

    // null = auto-detect per block; true = force Arabic->English; false = force English->Arabic
    private fun forcedLanguageDirection(): Boolean? {
        return when (getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(PREF_LANGUAGE_MODE, 0)) {
            1 -> true
            2 -> false
            else -> null
        }
    }

    // ---------------------------------------------------------------------
    // Tap bubble = TOGGLE: if a translation is already showing, hide it
    // (this is how you manually stop a translation). Otherwise translate
    // what's currently visible. If the accessibility service has been
    // killed by the OS in the background (common on some phone brands),
    // jump straight to the Accessibility settings screen instead of just
    // showing a toast, since re-enabling it there is the actual fix.
    // ---------------------------------------------------------------------

    private fun onBubbleClicked() {
        if (overlayIsShowing) {
            removeWordOverlay()
            setBubbleColor(COLOR_IDLE)
            return
        }
        if (isTranslating) {
            Toast.makeText(this, "Still translating\u2026", Toast.LENGTH_SHORT).show()
            return
        }

        val accessibilityService = TranslationAccessibilityService.instance
        if (accessibilityService == null) {
            Toast.makeText(
                this,
                "Accessibility service was turned off (often by battery settings) - re-enabling it now",
                Toast.LENGTH_LONG
            ).show()
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }

        isTranslating = true
        setBubbleColor(COLOR_TRANSLATING)
        Toast.makeText(this, "Translating\u2026", Toast.LENGTH_SHORT).show()

        val forcedDirection = forcedLanguageDirection()

        serviceScope.launch {
            // A hard ceiling on the whole translate flow: if anything hangs
            // (screenshot capture is known to occasionally never call back
            // on some devices, or ML Kit stalls), this guarantees the
            // bubble comes back to a usable state instead of getting stuck
            // showing "Translating..." forever.
            val didShow = withTimeoutOrNull(25_000L) {
                // Walk the accessibility tree off the main thread. On a
                // busy screen (lots of UI elements, e.g. social media
                // apps) this can take a noticeable moment, and doing it on
                // the main thread was blocking the whole app - and
                // occasionally made Android flag the accessibility
                // service as unresponsive and silently turn it off.
                // Raw blocks, unmerged - merging now happens once, later,
                // on the combined OCR+Accessibility list (mergeNearbyBlocks)
                // so fragments from BOTH sources can join the same cluster.
                val accessibilityBlocks = withContext(Dispatchers.Default) {
                    accessibilityService.extractVisibleText()
                }

                // Capture the screen once now (before any overlay is drawn
                // on top of it) - used both to sample real background
                // colors per block AND to OCR text baked into images/video
                // (memes, etc.) that Accessibility can never see, since
                // that text isn't a real UI element, just pixels.
                val screenshot = withTimeoutOrNull(8_000L) {
                    captureScreenshotOrNull(accessibilityService)
                }

                val ocrBlocks = screenshot?.let {
                    withTimeoutOrNull(6_000L) { runOcr(it) }
                } ?: emptyList()

                // Debug visibility (item 15 of what you asked for): record
                // exactly what each source found this time, viewable on
                // the "Last crash" screen. Screenshot == null here means
                // the screen capture itself failed or timed out (common
                // with playing video) - OCR never even got a chance to run.
                accessibilityService.log(
                    "Translate tap: screenshot=${if (screenshot != null) "captured ${screenshot.width}x${screenshot.height}" else "FAILED/timeout"}, " +
                        "accessibility_blocks=${accessibilityBlocks.size} [${accessibilityBlocks.take(5).joinToString(" | ") { it.text.take(40) }}], " +
                        "ocr_blocks=${ocrBlocks.size} [${ocrBlocks.take(5).joinToString(" | ") { it.text.take(40) }}]"
                )

                // Don't translate the same text twice - and when a region
                // is covered by BOTH sources, prefer OCR's reading. OCR
                // reads only the pixels actually rendered on screen (like
                // Google Lens does), while Accessibility's tree often
                // carries extra structural noise for the same visual area
                // (avatar alt-text, separate count/label nodes, redundant
                // container descriptions) that makes translations look
                // scattered. Accessibility still covers anything OCR
                // didn't manage to read.
                val accessibilityOnlyBlocks = accessibilityBlocks.filterNot { axBlock ->
                    ocrBlocks.any { ocrBlock ->
                        val overlap = android.graphics.Rect()
                        if (overlap.setIntersect(ocrBlock.bounds, axBlock.bounds)) {
                            val overlapArea = overlap.width().toLong() * overlap.height()
                            val axArea = axBlock.bounds.width().toLong() * axBlock.bounds.height()
                            axArea > 0 && overlapArea.toDouble() / axArea > 0.4
                        } else false
                    }
                }

                // Filter junk FIRST, then merge what's left. Filtering
                // after merging would risk a real sentence silently
                // absorbing an adjacent "Reply"/"Follow" label into its
                // text instead of that label being dropped outright.
                val cleaned = (ocrBlocks + accessibilityOnlyBlocks)
                    .filterNot { isLikelyJunkNumber(it.text) }
                    .filterNot { isLikelyUiLabel(it) }

                val limited = mergeNearbyBlocks(cleaned)
                    // If there's more than fits, keep the most substantive
                    // content (longer text) rather than whatever happened
                    // to come first in the screen's element order - a real
                    // paragraph should never lose its spot to a stray
                    // three-letter label.
                    .sortedByDescending { it.text.length }
                    .take(MAX_BLOCKS)

                if (limited.isEmpty()) {
                    return@withTimeoutOrNull false
                }

                // Each block's own text decides its own translation
                // direction (unless the user forced one in settings) - see
                // TranslatorHelper.translateBatch for why this matters on
                // mixed-language screens.
                val results = translatorHelper.translateBatch(
                    texts = limited.map { it.text },
                    forcedDirection = forcedDirection
                )

                val translatedBlocks = limited.zip(results).mapNotNull { (block, result) ->
                    result.getOrNull()?.let { translated -> block to translated }
                }

                if (translatedBlocks.isNotEmpty()) {
                    showWordOverlay(translatedBlocks, screenshot)
                    true
                } else {
                    false
                }
            }

            isTranslating = false
            if (didShow == true) {
                setBubbleColor(COLOR_ACTIVE)
            } else {
                val message = if (didShow == null) "Translation timed out" else "Translation failed"
                Toast.makeText(this@FloatingBubbleService, message, Toast.LENGTH_SHORT).show()
                setBubbleColor(COLOR_IDLE)
            }
        }
    }

    // ---------------------------------------------------------------------
    // In-place overlay: one small independent window PER translated block,
    // placed exactly over that block's original position with a background
    // color sampled from the real screen behind it (so it blends in like
    // Google Lens, not a plain white box), and individually draggable.
    // Because each window only covers its own text (not the full screen),
    // the empty space between them is untouched and taps still reach the
    // app underneath normally.
    // ---------------------------------------------------------------------

    private fun showWordOverlay(items: List<Pair<ScreenTextBlock, String>>, screenshot: Bitmap?) {
        removeWordOverlay()

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val scale = textSizeScale()
        val screenWidth = resources.displayMetrics.widthPixels
        for ((block, translated) in items) {
            val backgroundColor = sampleBackgroundColor(screenshot, block.bounds)
            // Cap width by remaining screen space from this block's left
            // edge, not by the original element's width - a narrow icon
            // button's original bounds are often much narrower than its
            // Arabic translation, and forcing that width makes the text
            // wrap into an ugly single-letter-per-line vertical stack.
            val maxWidth = (screenWidth - block.bounds.left - dpToPx(8)).coerceAtLeast(dpToPx(60))
            // Full in-place replacement, the way Google Lens does it, means
            // this box must NEVER be smaller than the area it's replacing -
            // only WRAP_CONTENT (no minWidth/minHeight) was set before, so
            // whenever the translation measured narrower or shorter than
            // the original (Arabic is very often MORE COMPACT than
            // English for the same sentence), the box shrank to fit the
            // translation and left a sliver of the original text exposed
            // on the edge. Pinning both to at least the original block's
            // size guarantees full coverage regardless of which direction
            // the translation differs in length; maxWidth still lets it
            // grow wider when the translation genuinely needs more room.
            val minWidth = block.bounds.width().coerceAtMost(maxWidth)
            val minHeight = block.bounds.height()
            val label = TextView(this).apply {
                text = translated
                setTextColor(readableTextColorFor(backgroundColor))
                setBackgroundColor(backgroundColor) // blends into the real background instead of a plain white box
                textSize = autoTextSizeSp(translated) * scale
                setPadding(10, 4, 10, 4)
                // No maxLines cap: a translation (Arabic especially) is
                // often MORE lines than the original once word-wrapped at
                // this width, and capping at 2 silently cut off everything
                // past that - a whole paragraph's translation was ending
                // mid-sentence with the rest of the original English just
                // left showing underneath it, uncovered. WRAP_CONTENT
                // height below now grows to fit however many lines the
                // real translated text needs, same as Google Lens does.
                this.maxWidth = maxWidth
                this.minWidth = minWidth
                this.minHeight = minHeight
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = block.bounds.left
                y = block.bounds.top
            }

            windowManager.addView(label, params)
            attachLabelTouchListener(label, params)
            translatedLabelViews.add(label to params)
        }

        overlayIsShowing = true
    }

    // Shrink text a bit when the translation is noticeably longer than the
    // original word/phrase would normally hold, so it's less likely to
    // overflow or wrap excessively.
    private fun autoTextSizeSp(translated: String): Float = when {
        translated.length > 80 -> 9f
        translated.length > 40 -> 10f
        else -> 12f
    }

    // Drag to move an individual translated label, or long-press to copy
    // its text to the clipboard.
    private fun attachLabelTouchListener(label: TextView, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var longPressTriggered = false
        var lastTapTime = 0L
        val longPressRunnable = Runnable {
            longPressTriggered = true
            copyLabelText(label)
        }

        label.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    longPressTriggered = false
                    mainHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > CLICK_DRAG_THRESHOLD || abs(dy) > CLICK_DRAG_THRESHOLD) {
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                    params.x = initialX + dx
                    params.y = initialY + dy
                    runCatching { windowManager.updateViewLayout(view, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    val dx = abs(event.rawX - initialTouchX)
                    val dy = abs(event.rawY - initialTouchY)
                    val isTap = dx < CLICK_DRAG_THRESHOLD && dy < CLICK_DRAG_THRESHOLD
                    if (!longPressTriggered && isTap) {
                        // Double-tap = read the translation aloud; a single
                        // tap does nothing extra (drag and long-press-copy
                        // already cover the other gestures).
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < DOUBLE_TAP_MS) {
                            lastTapTime = 0L
                            speakLabelText(label)
                        } else {
                            lastTapTime = now
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    true
                }
                else -> false
            }
        }
    }

    private fun copyLabelText(label: TextView) {
        vibrateTick()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Translation", label.text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    // Reads a translated label aloud, picking Arabic or English speech
    // based on which script the translated text is actually in - not the
    // app's overall setting, since forced/auto mode can mix both per block.
    private fun speakLabelText(label: TextView) {
        val engine = tts ?: return
        val text = label.text.toString()
        val locale = if (translatorHelper.isArabicDominant(text)) {
            java.util.Locale("ar")
        } else {
            java.util.Locale.US
        }
        val result = engine.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Toast.makeText(this, "Voice for this language isn't installed", Toast.LENGTH_SHORT).show()
            return
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "translate_bubble_utterance")
    }

    private fun removeWordOverlay() {
        translatedLabelViews.forEach { (view, _) ->
            runCatching { windowManager.removeView(view) }
        }
        translatedLabelViews.clear()
        overlayIsShowing = false
    }

    private fun dpToPx(dp: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics
    ).toInt()
}
