package io.github.moronigranja.alabasterdawn

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.window.OnBackInvokedDispatcher
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.File

/**
 * Hosts the game: the user's own folder served from SAF, the Node/NW.js shim injected at document
 * start, native controller input, saves in a second SAF folder that keeps the Steam layout, and a
 * copy of the diagnostics record in that folder so it survives a force-stop.
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
    private var padLayoutStore: PadLayoutStore? = null
    private var webView: WebView? = null
    private var padView: OnScreenPadView? = null
    private var menuView: SideMenuView? = null
    private var hideWithController = true
    private var viewAlign = ViewAlign.DEFAULT
    private var statsEnabled = false
    private var shimSource: String = ""

    /** Whether the record is also kept as [LogFile.FILE] in the saves folder. */
    private var logToSaves = true

    /* Written from the diagnostic sink (the JS bridge thread) and read by the watchdog (main), so
     * these are volatile; only the main thread decides to write. */
    @Volatile
    private var logDirty = false
    @Volatile
    private var logWriting = false
    @Volatile
    private var logFailures = 0

    /* Whether the previous session's record has been read (or found absent) yet: until then nothing
     * is flushed, so the write cannot clobber the record that launch is about to carry over. */
    @Volatile
    private var previousRecordRead = false

    /* Whether a previous session's lines are already in the ring, so a second read (a saves folder
     * picked mid-session) does not carry the same record twice. */
    @Volatile
    private var previousRecordCarried = false

    /** The app's own log and frame clock; everything device-specific is reported through it. */
    private lateinit var diag: Diag
    private var assetHandler: GameAssetHandler? = null

    /** The last report the injected shim made, shown in the side menu (a boot stall names itself). */
    @Volatile
    private var lastEngineReport: String = "-"

    /* The watchdog that tells a blocked page apart from a page waiting for a hung asset read. */
    private val watchdog = Handler(Looper.getMainLooper())
    private var webViewStartedAt = 0L
    private var lastSilentLogAt = 0L

    /**
     * Whether the page is meant to be running right now: the engine's loop only polls once per frame
     * while it has window focus, and the port pauses it on purpose (app backgrounded, or the
     * diagnostics dialog in front). Silence in those states is expected, not a hang.
     */
    @Volatile
    private var engineActive = false

    /** Last axes we logged, so a held stick does not flood logcat. */
    private val lastLoggedAxes = FloatArray(4)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREF, MODE_PRIVATE)
        /* Everything logged here is also written to logcat, so nothing is lost on a device where
         * the diagnostics panel cannot be read. The sink also marks the persistent record dirty and
         * posts the auto-off check on the shim's boot report: it runs inside Diag's lock, so it must
         * not call back into Diag (a field write and a post allocate nothing per line). */
        diag = Diag().also { it.attachSink { line ->
            Log.i(TAG, line)
            if (line.contains(" ENGINE ")) lastEngineReport = line.substringAfter("ENGINE ")
            logDirty = true
            if (line.contains(" ENGINE boot: ")) watchdog.post { autoDisableLog() }
        } }
        diag.line("port ${appVersion()} on ${Build.MANUFACTURER} ${Build.MODEL}, Android " +
            "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}, ${Build.SUPPORTED_ABIS.firstOrNull()})")
        gameTreeUri = validateGrant(prefs.getString(KEY_GAME, null), wantWrite = false)
        savesTreeUri = validateGrant(prefs.getString(KEY_SAVES, null), wantWrite = true)
        shimSource = readAsset(SHIM_ASSET)
        hideWithController = prefs.getBoolean(KEY_HIDE_CONTROLLER, true)
        viewAlign = ViewAlign.fromWire(prefs.getString(KEY_VIEW_ALIGN, null))
        statsEnabled = prefs.getBoolean(KEY_STATS, false)
        logToSaves = prefs.getBoolean(LogFile.PREF_KEY, true)
        /* The store is opened here and not at START, and the previous session's record is read back
         * into the ring: a user who freezes and restarts expects the panel (and the file) to still
         * show what happened, not an empty record from the launch that follows. */
        saveStore = openSaveStore()
        carryPreviousRecord(saveStore!!)
        buildPreGameUi()
        applyImmersive()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            /* On Android 16 with targetSdk 36 the framework routes back to the dispatcher and never
             * calls onBackPressed; older devices only ever use onBackPressed. */
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) {
                diag.line("back: dispatcher (${if (webView != null) "game" else "pre-game"})")
                handleBack()
            }
        }
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
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    startButton,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                /* A device-only failure is reported from here: the record names the WebView, the GL
                 * backend and what the engine was doing, without adb. */
                addView(Button(this@PortActivity).apply {
                    text = "Diagnostics"
                    setOnClickListener { showDiagnostics() }
                })
            }
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
            diag.line("no document picker: ${e.message}")
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
            diag.line("grant refused for ${GameFiles.displayNameOf(uri)} (flags=$flags)")
            status.text = "Access to ${GameFiles.displayNameOf(uri)} could not be kept. Choose it again."
            return
        }
        if (!wantWrite && (flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
            /* Harmless, but the game folder is never written to. */
            diag.line("game folder grant is writable; writes are still refused by the bridge")
        }
        if (wantWrite) {
            savesTreeUri = uri
            prefs.edit().putString(KEY_SAVES, uri.toString()).apply()
            /* The store was opened at startup, before this pick: follow the folder the user just
             * pointed at, and read its previous record before a flush can overwrite it. */
            val fresh = openSaveStore()
            saveStore = fresh
            if (!previousRecordCarried) carryPreviousRecord(fresh)
        } else {
            gameTreeUri = uri
            prefs.edit().putString(KEY_GAME, uri.toString()).apply()
        }
        diag.line("granted ${if (wantWrite) "saves" else "game"}: $uri (flags=$flags)")
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
            diag.line("stored grant for $uri is no longer valid; asking again")
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
        /* The saves store was opened at startup; the flush chain starts here rather than in
         * launchWebView, because reading a big game folder can take a while and a failure there has
         * to leave a record on disk, not only on screen. launchWebView re-posts this same chain. */
        if (saveStore == null) saveStore = openSaveStore()
        watchdog.postDelayed(watchdogTick, WATCHDOG_TICK_MS)
        diag.line("indexing $tree")
        val started = System.currentTimeMillis()
        Thread({
            val built = try {
                GameFiles.indexTree(contentResolver, tree)
            } catch (e: Exception) {
                Log.e(TAG, "indexing failed", e)
                diag.line("indexing failed: $e")
                null
            }
            runOnUiThread {
                if (built == null) {
                    status.text = "Could not read that folder."
                    startButton.isEnabled = true
                    return@runOnUiThread
                }
                index = built
                diag.line("indexed ${built.size} entries in ${System.currentTimeMillis() - started}ms")
                padLayoutStore = PadLayoutStore(prefs, saveStore!!)
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
        diag.line("webView ${webViewPackage()} document-start scripts=" + !injectShim +
            " ua=${webSettingsUserAgent()}")
        if (injectShim) {
            diag.line("document-start scripts unsupported; the shim is injected via terra/index.html")
        }
        val handler = GameAssetHandler(
            contentResolver, gameTreeUri!!, built, assets, injectShim, diag
        )
        assetHandler = handler
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/game/", handler)
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
        val telemetry = Telemetry(this)
        view.addJavascriptInterface(
            AdaBridge(
                bridge,
                viewAlign = { viewAlign },
                statsEnabled = { statsEnabled },
                telemetry = telemetry,
                diag = diag,
            ),
            BRIDGE_NAME
        )
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
        /* The pad sits above the WebView, so it receives touches first; when it does not claim a
         * gesture (a hidden pad, or a tap on empty space with it hidden) it returns false and the
         * FrameLayout passes the event on to the WebView. */
        val frame = FrameLayout(this)
        frame.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        val pad = OnScreenPadView(this).apply {
            padEnabled = prefs.getBoolean(KEY_PAD, true)
            hideWithController = this@PortActivity.hideWithController
            layout = padLayoutStore?.load() ?: PadLayout()
            onToggle = { prefs.edit().putBoolean(KEY_PAD, it).apply() }
            onLayoutChanged = { padLayoutStore?.save(it) }
            onFirstTouch = { resumeAudio() }
        }
        padView = pad
        frame.addView(
            pad,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        val menu = SideMenuView(this).apply {
            setHideWithController(this@PortActivity.hideWithController)
            setStatsEnabled(statsEnabled)
            setLogToSaves(logToSaves)
            setAlign(viewAlign)
            onHideWithController = {
                this@PortActivity.hideWithController = it
                prefs.edit().putBoolean(KEY_HIDE_CONTROLLER, it).apply()
                padView?.hideWithController = it
            }
            onStatsEnabled = {
                this@PortActivity.statsEnabled = it
                prefs.edit().putBoolean(KEY_STATS, it).apply()
            }
            /* An explicit tap ends the auto-off for good, whichever way it went. */
            onLogToSaves = {
                this@PortActivity.logToSaves = it
                prefs.edit()
                    .putBoolean(LogFile.PREF_KEY, it)
                    .putBoolean(LogFile.PREF_SET_KEY, true)
                    .apply()
                logDirty = true
                diag.line("log file " + (if (it) "on" else "off") + ": " + LogFile.FILE)
            }
            onAlign = {
                this@PortActivity.viewAlign = it
                prefs.edit().putString(KEY_VIEW_ALIGN, it.wire).apply()
            }
            onExit = { finish() }
            onDiagnostics = { showDiagnostics() }
            onScrimTap = { closeMenu() }
        }
        frame.addView(
            menu,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        menuView = menu
        setContentView(frame)
        view.requestFocus()
        setStatus("Loading the game…")
        diag.line("loading $INDEX_URL")
        webViewStartedAt = monotonicMs()
        watchdog.removeCallbacksAndMessages(null)
        watchdog.postDelayed(watchdogTick, WATCHDOG_TICK_MS)
        view.loadUrl(INDEX_URL)
    }

    /**
     * Runs while the game is up. The engine polls `getGamepadJson()` once per frame, so silence
     * there means the page's JS thread stopped - a different failure from a page that is alive but
     * waiting for an asset read that never returns. Both are reported with the names involved, which
     * is what turns a screenshot of a frozen bar into something actionable.
     */
    private val watchdogTick = object : Runnable {
        override fun run() {
            flushLogIfDirty()
            val now = monotonicMs()
            val silent = diag.silentMs()
            if (webView != null && engineActive && now - webViewStartedAt > SILENT_MS &&
                silent > SILENT_MS && now - lastSilentLogAt > SILENT_LOG_MS
            ) {
                lastSilentLogAt = now
                diag.line("engine silent for ${silent}ms; last bridge call: ${diag.lastBridgeCall()}")
                for (stuck in assetHandler?.stuck(STUCK_MS).orEmpty()) {
                    diag.line("asset read stuck: $stuck")
                }
            }
            watchdog.postDelayed(this, WATCHDOG_TICK_MS)
        }
    }

    private inner class PortWebViewClient(private val loader: WebViewAssetLoader) : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            diag.line("render process gone (crashed=${detail.didCrash()}); rebuilding the WebView")
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

    /* ------------------------------------------------------------- diagnostics ---- */

    private fun showDiagnostics() {
        diag.line("diagnostics opened")
        val text = diag.snapshot(diagFacts())
        DiagnosticsDialog(this, text) { shareDiagnostics(text) }.apply {
            setOnDismissListener {
                /* The dialog held window focus, which blurred the page; hand it back like closeMenu. */
                webView?.requestFocus()
                setPageFocus(true)
                engineActive = true
            }
            /* The dialog blurs the page, and the engine stops its loop on `blur` by design: silence
             * while it is open is expected, so the watchdog must not call it a hang. */
            engineActive = false
            show()
        }
    }

    /** What the record is read against: the device, the WebView, the folders and the live counters. */
    private fun diagFacts(): List<String> = listOf(
        "app ${appVersion()} on ${Build.MANUFACTURER} ${Build.MODEL}, Android " +
            "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}, ${Build.SUPPORTED_ABIS.firstOrNull()})",
        "webView ${webViewPackage()} document-start scripts=" +
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT),
        "game files: ${GameFiles.displayNameOf(gameTreeUri) ?: "(not set)"} " +
            "(${if (index == null) "not indexed" else "${index!!.size} entries"})",
        "saves: ${savesStatusLine()}",
        "engine: silent for ${diag.silentMs()}ms, last bridge call: ${diag.lastBridgeCall()}",
        assetHandler?.counters() ?: "assets: none served yet",
    )

    /**
     * Reads the previous session's record out of [store] and carries it into the ring, off the main
     * thread (it is a SAF read). No flush may run until it lands (see [previousRecordRead]), or the
     * first write would overwrite the very record being carried over.
     */
    private fun carryPreviousRecord(store: SaveStore) {
        Thread({
            val text = if (store.exists(LogFile.FILE)) store.read(LogFile.FILE) else null
            val previous = text?.let { LogFile.previousLines(it, diag.maxLines()) }.orEmpty()
            if (previous.isNotEmpty()) {
                diag.carryOver(previous)
                previousRecordCarried = true
            }
            previousRecordRead = true
        }, "ada-log-read").start()
    }

    /**
     * Keeps [LogFile.FILE] in the saves folder current, at most every watchdog tick. The snapshot is
     * taken here (main thread, the same text the panel shows) and written on [writeLogAsync]'s own
     * thread: the stores under it are SAF, and a blocked write must never block the UI.
     */
    private fun flushLogIfDirty() {
        if (!previousRecordRead) return
        if (!LogFile.shouldFlush(logToSaves, logDirty, logWriting, logFailures)) return
        val store = saveStore ?: return
        logDirty = false
        logWriting = true
        writeLogAsync(store, diag.snapshot(diagFacts()))
    }

    /**
     * If this write hangs inside the provider, [logWriting] never clears and no further write is
     * attempted: no ANR, no spin. [LogFile.MAX_FAILURES] bounds the case where it returns an error.
     */
    private fun writeLogAsync(store: SaveStore, text: String) {
        Thread({
            val ok = store.write(LogFile.FILE, text)
            watchdog.post {
                logWriting = false
                if (ok) {
                    logFailures = 0
                } else if (++logFailures <= LogFile.MAX_FAILURES) {
                    diag.line("could not write ${LogFile.FILE} (attempt $logFailures)")
                }
            }
        }, "ada-log").start()
    }

    /**
     * Called when the shim reports a completed boot: the record is most useful for a boot that
     * *fails*, so by default it stops growing once one succeeds. Only while the user has never
     * touched the toggle - and the file itself stays on disk either way.
     */
    private fun autoDisableLog() {
        if (!LogFile.shouldAutoDisable(prefs.getBoolean(LogFile.PREF_SET_KEY, false), logToSaves)) {
            return
        }
        logToSaves = false
        prefs.edit().putBoolean(LogFile.PREF_KEY, false).apply() // PREF_SET_KEY stays false: untouched
        menuView?.setLogToSaves(false)
        diag.line("log file off: the game completed its first boot")
        /* One final write, after the toggle went off: this is an explicit one-shot, not the
         * periodic path, so what happened up to and including this boot is on disk. */
        saveStore?.let { writeLogAsync(it, diag.snapshot(diagFacts())) }
    }

    /** Writes the record next to the app and hands it to any app that can send text. */
    private fun shareDiagnostics(text: String): String {
        val dir = getExternalFilesDir(null) ?: filesDir
        val file = File(dir, "diagnostics-${System.currentTimeMillis() / 1000}.txt")
        try {
            file.writeText(text)
        } catch (e: Exception) {
            Log.e(TAG, "cannot write $file", e)
            return "could not write the file: ${e.message}"
        }
        diag.line("diagnostics written to ${file.absolutePath}")
        return try {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Alabaster Dawn Android port diagnostics")
                        putExtra(Intent.EXTRA_TEXT, text)
                    },
                    "Share diagnostics"
                )
            )
            "sent from ${file.absolutePath}"
        } catch (e: ActivityNotFoundException) {
            "no app to share it with; the record is at ${file.absolutePath}"
        }
    }

    private fun appVersion(): String = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        @Suppress("DEPRECATION")
        "${info.versionName} (${info.versionCode})"
    } catch (e: Exception) {
        "?"
    }

    /** The WebView implementation actually running: a device without Play may be far behind. */
    private fun webViewPackage(): String = try {
        WebViewCompat.getCurrentWebViewPackage(this)?.let { "${it.packageName} ${it.versionName}" }
            ?: "unknown (no provider package)"
    } catch (e: Exception) {
        "?"
    }

    private fun webSettingsUserAgent(): String = try {
        WebSettings.getDefaultUserAgent(this)
    } catch (e: Exception) {
        "?"
    }

    private fun monotonicMs(): Long = System.nanoTime() / 1_000_000

    /** Back opens the port's menu, closes it when it is open; pre-game it still exits the app. */
    private fun handleBack() {
        val menu = menuView
        if (menu == null) {
            finish()
        } else if (menu.isOpen) {
            closeMenu()
        } else {
            openMenu()
        }
    }

    private fun openMenu() {
        val menu = menuView ?: return
        menu.setStatus(padView?.controllerInUse == true, savesStatusLine())
        menu.setLastEngineReport(lastEngineReport)
        menu.open()
    }

    /**
     * Closing re-focuses the WebView and re-dispatches the page focus the way `resumeAudio` does:
     * the panel blocks focus, but a stray blur would otherwise leave the engine's loop stopped
     * until the next resume or pad touch.
     */
    private fun closeMenu() {
        menuView?.close()
        webView?.requestFocus()
        setPageFocus(true)
    }

    /** Without a saves folder the port saves into app storage, which cannot be copied out. */
    private fun savesStatusLine(): String =
        savesTreeUri?.let { GameFiles.displayNameOf(it) } ?: "app storage (not exportable)"

    /* ------------------------------------------------------------------------- input ---- */

    /**
     * Back belongs to the port - it opens the menu while the game runs and exits the app before it
     * does - and it is taken here rather than left to the framework so that **every** Back reaches it:
     * a key event goes through the activity before the WebView can hand it to the page, which is what a
     * device report of "the physical Back button does not open the menu" (an AYN Odin 3) looks like from
     * the inside. `onBackInvokedDispatcher` (registered in `onCreate`) covers the gesture on API 33+,
     * and this covers the key on every version. Each path says which one it was in the record, so a
     * report like that can be told apart from a Back that never arrived at all.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                diag.line("back: key (${if (webView != null) "game" else "pre-game"})")
                handleBack()
            }
            return true
        }
        if (isGamepad(event.source)) {
            padView?.noteControllerActivity()
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
            padView?.noteControllerActivity()
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
        engineActive = true
    }

    override fun onPause() {
        engineActive = false
        /* An edit in progress is saved when the app goes to the background, so it is not lost if
         * the process is killed. */
        padView?.commitIfEditing()
        /* Blur before the timers stop, or the AudioContext suspension waits for the app to come
         * back. pauseTimers() then covers the loop the engine drives with setInterval (an fps
         * below 60) and any other page timer. */
        setPageFocus(false)
        webView?.onPause()
        webView?.pauseTimers()
        super.onPause()
    }

    override fun onDestroy() {
        watchdog.removeCallbacksAndMessages(null)
        try {
            webView?.apply {
                removeJavascriptInterface(BRIDGE_NAME)
                destroy()
            }
        } catch (e: Exception) {
            Log.w(TAG, "WebView teardown failed", e)
        }
        webView = null
        padView = null
        menuView = null
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

        /* Whether the on-screen pad draws its controls. */
        private const val KEY_PAD = "on_screen_pad"
        /* Whether a controller in use hides the on-screen overlay (the side menu's switch). */
        private const val KEY_HIDE_CONTROLLER = "hide_with_controller"
        /* Where the picture sits vertically: a ViewAlign.wire value. */
        private const val KEY_VIEW_ALIGN = "view_align"
        /* Whether the shim draws its frame-rate/resolution/battery/thermal readout. */
        private const val KEY_STATS = "stats_overlay"
        private const val REQ_GAME = 101
        private const val REQ_SAVES = 102
        private const val BRIDGE_NAME = "AdaBridge"
        private const val SHIM_ASSET = "ada-shim.js"
        private const val ORIGIN = "https://appassets.androidplatform.net"
        private const val INDEX_URL = "$ORIGIN/game/terra/index.html"

        /* Diagnostics: how long the page's frame clock may go quiet before it is reported, how often
         * that is repeated, how long an asset read may be unfinished first, and the poll interval. */
        private const val SILENT_MS = 5000L
        private const val SILENT_LOG_MS = 10000L
        private const val STUCK_MS = 5000L
        private const val WATCHDOG_TICK_MS = 2000L

        /* The engine's only pause/resume entry point (see setPageFocus). A plain non-bubbling
         * Event is all it takes: both listeners sit on `window` itself. */
        private const val PAGE_BLUR_JS = "window.dispatchEvent(new Event('blur'))"
        private const val PAGE_FOCUS_JS = "window.dispatchEvent(new Event('focus'))"
    }
}
