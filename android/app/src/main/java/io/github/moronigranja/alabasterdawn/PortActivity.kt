package io.github.moronigranja.alabasterdawn

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * Hosts the game: the user's own folder served from SAF, the Node/NW.js shim injected at document
 * start, native controller input, and saves in a second SAF folder that keeps the Steam layout.
 *
 * Nothing under the game folder is ever written to.
 */
class PortActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var gameLabel: TextView
    private lateinit var savesLabel: TextView
    private lateinit var status: TextView
    private lateinit var startButton: Button

    private var gameTreeUri: Uri? = null
    private var savesTreeUri: Uri? = null
    private var index: GameIndex? = null
    private var fsBridge: FsBridge? = null
    private var saveStore: SaveStore? = null
    private var webView: WebView? = null
    private var shimSource: String = ""

    /** Last axes we logged, so a held stick does not flood logcat. */
    private val lastLoggedAxes = FloatArray(4)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREF, MODE_PRIVATE)
        gameTreeUri = validateGrant(prefs.getString(KEY_GAME, null), wantWrite = false)
        savesTreeUri = validateGrant(prefs.getString(KEY_SAVES, null), wantWrite = true)
        shimSource = readAsset(SHIM_ASSET)
        buildPreGameUi()
        applyImmersive()
    }

    /* ---------------------------------------------------------------- pre-game screen ---- */

    private fun buildPreGameUi() {
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.BLACK)
        }
        root.addView(
            TextView(this).apply {
                text = getString(R.string.app_name)
                textSize = 22f
                setTextColor(Color.WHITE)
            }
        )
        gameLabel = folderRow(root, "game") { pick(REQ_GAME, wantWrite = false) }
        savesLabel = folderRow(root, "saves") { pick(REQ_SAVES, wantWrite = true) }
        status = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(status)
        startButton = Button(this).apply {
            text = "Start"
            setOnClickListener { startGame() }
        }
        root.addView(
            startButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        setContentView(root)
        refreshUi()
    }

    private fun folderRow(parent: LinearLayout, which: String, onPick: () -> Unit): TextView {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val label = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
        }
        row.addView(
            label,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(Button(this).apply {
            text = if (which == "game") "Choose game files" else "Choose saves folder"
            setOnClickListener { onPick() }
        })
        parent.addView(row)
        return label
    }

    private fun refreshUi() {
        gameLabel.text = "Game files: " + (GameFiles.displayNameOf(gameTreeUri) ?: "(not set)")
        savesLabel.text = "Saves: " + (GameFiles.displayNameOf(savesTreeUri) ?: "(not set)")
        startButton.isEnabled = gameTreeUri != null
        if (status.length() == 0) {
            status.text = when {
                gameTreeUri == null -> "Pick the folder that contains the game's terra/ directory."
                savesTreeUri == null ->
                    "No saves folder: saves will be kept inside the app and cannot be copied out."
                else -> "Ready: ${GameFiles.displayNameOf(gameTreeUri)} + ${GameFiles.displayNameOf(savesTreeUri)}"
            }
        }
    }

    private fun pick(requestCode: Int, wantWrite: Boolean) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (wantWrite) addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, requestCode)
        } catch (e: Exception) {
            Log.e(TAG, "no document picker", e)
            status.text = "No document picker available: ${e.message}"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_GAME && requestCode != REQ_SAVES) return
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val wantWrite = requestCode == REQ_SAVES
        /* The picker only offers what we asked for in the intent, so mirror those flags. */
        var flags = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (wantWrite) flags = flags or (data.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        var granted = false
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
            granted = true
        } catch (e: SecurityException) {
            Log.e(TAG, "cannot persist grant for $uri (flags=$flags)", e)
        }
        if (!granted) {
            status.text = "Access to ${GameFiles.displayNameOf(uri)} could not be kept. Choose it again."
            return
        }
        if (!wantWrite && (flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
            /* Harmless, but the game folder is never written to. */
            Log.i(TAG, "game folder grant is writable; writes are still refused by the bridge")
        }
        if (wantWrite) {
            savesTreeUri = uri
            prefs.edit().putString(KEY_SAVES, uri.toString()).apply()
        } else {
            gameTreeUri = uri
            prefs.edit().putString(KEY_GAME, uri.toString()).apply()
        }
        Log.i(TAG, "granted ${if (wantWrite) "saves" else "game"}: $uri (flags=$flags)")
        status.text = ""
        refreshUi()
    }

    /** Drops a stored grant the user has since revoked, instead of failing later mid-boot. */
    private fun validateGrant(stored: String?, wantWrite: Boolean): Uri? {
        if (stored == null) return null
        val uri = try {
            Uri.parse(stored)
        } catch (e: Exception) {
            return null
        }
        val held = contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        val ok = held != null && held.isReadPermission && (!wantWrite || held.isWritePermission)
        if (!ok) {
            Log.w(TAG, "stored grant for $uri is no longer valid; asking again")
            return null
        }
        return uri
    }

    /* ------------------------------------------------------------------------- game ---- */

    private fun startGame() {
        val tree = gameTreeUri ?: return
        if (index != null) return
        startButton.isEnabled = false
        setStatus("Indexing game files…")
        Log.i(TAG, "indexing $tree")
        val started = System.currentTimeMillis()
        Thread({
            val built = try {
                GameFiles.indexTree(contentResolver, tree)
            } catch (e: Exception) {
                Log.e(TAG, "indexing failed", e)
                null
            }
            runOnUiThread {
                if (built == null) {
                    status.text = "Could not read that folder."
                    startButton.isEnabled = true
                    return@runOnUiThread
                }
                index = built
                Log.i(TAG, "indexed in ${System.currentTimeMillis() - started} ms")
                saveStore = openSaveStore()
                fsBridge = FsBridge(contentResolver, tree, built, saveStore!!)
                status.text = "Loaded ${built.size} files."
                launchWebView()
            }
        }, "ada-index").start()
    }

    private fun openSaveStore(): SaveStore {
        val uri = savesTreeUri
        if (uri != null) {
            try {
                return SafStore(contentResolver, uri)
            } catch (e: Exception) {
                Log.e(TAG, "saves tree unusable, falling back to app storage", e)
            }
        }
        Log.w(TAG, "no saves folder: saving into app storage (not exportable)")
        return FileStore.appPrivate(this)
    }

    private fun launchWebView() {
        val built = index ?: return
        val bridge = fsBridge ?: return
        val injectShim = !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (injectShim) {
            Log.w(TAG, "document-start scripts unsupported; the shim is injected via terra/index.html")
        }
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler(
                "/game/",
                GameAssetHandler(contentResolver, gameTreeUri!!, built, assets, injectShim)
            )
            .build()
        val view = WebView(this)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = true
            setSupportZoom(false)
            builtInZoomControls = false
            loadWithOverviewMode = false
            useWideViewPort = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        if (isDebuggable()) WebView.setWebContentsDebuggingEnabled(true)
        view.setBackgroundColor(Color.BLACK)
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.addJavascriptInterface(AdaBridge(bridge), BRIDGE_NAME)
        if (!injectShim) {
            try {
                WebViewCompat.addDocumentStartJavaScript(view, shimSource, setOf(ORIGIN))
            } catch (e: Exception) {
                Log.e(TAG, "document-start injection failed", e)
            }
        }
        view.webViewClient = PortWebViewClient(loader)
        /* Audio can stay suspended until a real gesture; a tap silently nudges it. */
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) resumeAudio()
            false
        }
        webView = view
        setContentView(view)
        view.requestFocus()
        Log.i(TAG, "loading $INDEX_URL")
        view.loadUrl(INDEX_URL)
    }

    private inner class PortWebViewClient(private val loader: WebViewAssetLoader) : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.e(TAG, "render process gone (crashed=${detail.didCrash()}); rebuilding the WebView")
            rebuildWebView()
            return true
        }
    }

    private fun rebuildWebView() {
        val old = webView
        webView = null
        try {
            old?.apply {
                loadUrl("about:blank")
                destroy()
            }
        } catch (e: Exception) {
            Log.w(TAG, "destroying the dead WebView failed", e)
        }
        if (index != null && fsBridge != null) launchWebView()
    }

    private fun setStatus(text: String) {
        status.text = text
        Log.i(TAG, text)
    }

    /* ------------------------------------------------------------------------- input ---- */

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isGamepad(event.source)) {
            Gamepad.onKey(event)
            if (event.action == KeyEvent.ACTION_DOWN) {
                Log.d(TAG, "key ${KeyEvent.keyCodeToString(event.keyCode)} -> pad")
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (isGamepad(event.source)) {
            Gamepad.onMotion(event)
            logAxesIfChanged()
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun isGamepad(source: Int): Boolean =
        (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

    private fun logAxesIfChanged() {
        val json = Gamepad.json()
        if (json.isEmpty()) return
        var changed = false
        try {
            val axes = JSONObject(json).getJSONArray("axes")
            for (i in lastLoggedAxes.indices) {
                val v = axes.getDouble(i).toFloat()
                if (kotlin.math.abs(v - lastLoggedAxes[i]) > 0.02f) {
                    changed = true
                    lastLoggedAxes[i] = v
                }
            }
        } catch (e: Exception) {
            return
        }
        if (changed) Log.d(TAG, "axes=${lastLoggedAxes.joinToString(",")}")
    }

    /* ------------------------------------------------------------------- lifecycle ---- */

    /**
     * The engine stops itself on the page's `blur` (it suspends its AudioContext, makes the run
     * loop return before any update or draw, and clears held inputs) and starts again on `focus`.
     * An Android WebView dispatches neither when the app leaves the foreground, so the music and
     * the loop survive behind the launcher: measured on the SM-S908U1, the AudioTrack stayed
     * `active` and the renderer kept ~20% CPU. Dispatched here instead.
     */
    private fun setPageFocus(focused: Boolean) {
        webView?.evaluateJavascript(if (focused) PAGE_FOCUS_JS else PAGE_BLUR_JS, null)
    }

    override fun onResume() {
        super.onResume()
        applyImmersive()
        webView?.let {
            it.resumeTimers()
            it.onResume()
            it.requestFocus()
        }
        setPageFocus(true)
    }

    override fun onPause() {
        /* Blur before the timers stop, or the AudioContext suspension waits for the app to come
         * back. pauseTimers() then covers the loop the engine drives with setInterval (an fps
         * below 60) and any other page timer. */
        setPageFocus(false)
        webView?.onPause()
        webView?.pauseTimers()
        super.onPause()
    }

    override fun onDestroy() {
        try {
            webView?.apply {
                removeJavascriptInterface(BRIDGE_NAME)
                destroy()
            }
        } catch (e: Exception) {
            Log.w(TAG, "WebView teardown failed", e)
        }
        webView = null
        super.onDestroy()
    }

    /** A tap must restore audio if the WebView refused to start it without a gesture. */
    private fun resumeAudio() {
        setPageFocus(true)
    }

    private fun applyImmersive() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    private fun isDebuggable(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun readAsset(name: String): String = try {
        assets.open(name).use { String(it.readBytes(), Charsets.UTF_8) }
    } catch (e: Exception) {
        Log.e(TAG, "cannot read asset $name", e)
        ""
    }

    companion object {
        private const val TAG = "AdaPort"
        private const val PREF = "ada"
        private const val KEY_GAME = "game_tree_uri"
        private const val KEY_SAVES = "saves_tree_uri"
        private const val REQ_GAME = 101
        private const val REQ_SAVES = 102
        private const val BRIDGE_NAME = "AdaBridge"
        private const val SHIM_ASSET = "ada-shim.js"
        private const val ORIGIN = "https://appassets.androidplatform.net"
        private const val INDEX_URL = "$ORIGIN/game/terra/index.html"

        /* The engine's only pause/resume entry point (see setPageFocus). A plain non-bubbling
         * Event is all it takes: both listeners sit on `window` itself. */
        private const val PAGE_BLUR_JS = "window.dispatchEvent(new Event('blur'))"
        private const val PAGE_FOCUS_JS = "window.dispatchEvent(new Event('focus'))"
    }
}
