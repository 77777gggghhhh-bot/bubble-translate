package com.yourapp.translatebubble

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

data class ScreenTextBlock(
    val text: String,
    val bounds: Rect
)

class TranslationAccessibilityService : AccessibilityService() {

    companion object {
        var instance: TranslationAccessibilityService? = null
            private set
    }

    // Not private anymore: FloatingBubbleService uses this to log what
    // OCR/accessibility actually detected each time you translate, so you
    // can check the "Last crash" screen to see exactly what was found
    // versus missed - real visibility instead of guessing.
    fun log(message: String) {
        try {
            val prefs = getSharedPreferences("crash_log", Context.MODE_PRIVATE)
            val existing = prefs.getString("last_crash", "") ?: ""
            prefs.edit().putString("last_crash", "$existing\n[${System.currentTimeMillis()}] $message").commit()
        } catch (e: Exception) { }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        log("Service CONNECTED")
    }

    override fun onDestroy() {
        super.onDestroy()
        log("Service DESTROYED")
        instance = null
    }

    override fun onInterrupt() {
        log("Service INTERRUPTED")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    fun extractVisibleText(): List<ScreenTextBlock> {
        val root = rootInActiveWindow ?: return emptyList()
        val results = mutableListOf<ScreenTextBlock>()
        collectText(root, results)
        return results
    }

    fun extractVisibleTextAsString(): String {
        return extractVisibleText().joinToString(separator = "\n") { it.text }
    }

    // ---------------------------------------------------------------------
    // Capture the current screen so callers can sample the real background
    // color behind each piece of text - this is what lets the translation
    // blend in instead of sitting on a plain white box. Requires Android 11
    // (API 30) or newer; older devices get null and callers should fall
    // back to a plain background.
    // ---------------------------------------------------------------------
    fun captureScreenshot(onResult: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            log("captureScreenshot: skipped, API ${Build.VERSION.SDK_INT} < 30")
            onResult(null)
            return
        }
        val startedAt = System.currentTimeMillis()
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val elapsed = System.currentTimeMillis() - startedAt
                        val bitmap = try {
                            val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                                result.hardwareBuffer, result.colorSpace
                            )
                            val softwareBitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                            hardwareBitmap?.recycle()
                            softwareBitmap
                        } catch (e: Exception) {
                            log("captureScreenshot: onSuccess but bitmap conversion threw after ${elapsed}ms: ${e.javaClass.simpleName} ${e.message}")
                            null
                        } finally {
                            result.hardwareBuffer.close()
                        }
                        log("captureScreenshot: onSuccess after ${elapsed}ms, bitmap=${if (bitmap != null) "${bitmap.width}x${bitmap.height}" else "null"}")
                        onResult(bitmap)
                    }

                    override fun onFailure(errorCode: Int) {
                        val elapsed = System.currentTimeMillis() - startedAt
                        // errorCode meanings (AccessibilityService docs):
                        // 1=internal error, 2=no screen, 3=interval time short,
                        // 4=invalid display
                        log("captureScreenshot: onFailure after ${elapsed}ms, errorCode=$errorCode")
                        onResult(null)
                    }
                }
            )
        } catch (e: Exception) {
            log("captureScreenshot: takeScreenshot() threw immediately: ${e.javaClass.simpleName} ${e.message}")
            onResult(null)
        }
    }

    // parentText: the text just added for this node's direct ancestor (if
    // any), so a child that merely repeats it can be skipped instead of
    // being recorded as a second, separate block. This is extremely common
    // in real UI trees: a Button (or any clickable container) often has its
    // OWN text/contentDescription set to exactly match its inner TextView's
    // text, so both nodes pass the check below independently - without this,
    // you get the same phrase translated and shown twice, stacked, for one
    // single real button ("Start Floating Bubble" appearing doubled was
    // exactly this). Only skips an EXACT match against the direct parent
    // chain, so two unrelated sibling buttons that happen to share text
    // (e.g. two different "Follow" buttons for two different people) are
    // untouched - each still gets recorded once, correctly.
    private fun collectText(
        node: AccessibilityNodeInfo?,
        out: MutableList<ScreenTextBlock>,
        parentText: String? = null
    ) {
        if (node == null) return
        if (!node.isVisibleToUser) {
            recycleChildren(node)
            return
        }
        val nodeText = node.text?.toString()?.trim()
        val contentDesc = node.contentDescription?.toString()?.trim()
        val combined = when {
            !nodeText.isNullOrEmpty() -> nodeText
            !contentDesc.isNullOrEmpty() -> contentDesc
            else -> null
        }
        var textForChildren = parentText
        if (!combined.isNullOrEmpty() && combined != parentText) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) {
                out.add(ScreenTextBlock(combined, bounds))
            }
            textForChildren = combined
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            collectText(child, out, textForChildren)
            child?.recycle()
        }
    }

    private fun recycleChildren(node: AccessibilityNodeInfo) {
        for (i in 0 until node.childCount) {
            node.getChild(i)?.recycle()
        }
    }
}
