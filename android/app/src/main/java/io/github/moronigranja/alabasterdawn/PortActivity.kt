package io.github.moronigranja.alabasterdawn

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
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
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.File

/**
 * Hosts the game: the user's own folder served from SAF, the Node/NW.js shim injected at document
 * start, native controller, mouse and keyboard input, saves in a second SAF folder that keeps the
 * Steam layout, and a copy of the diagnostics record in that folder so it survives a force-stop.
 *
 * Nothing under the game folder is ever written to.
 */
class PortActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var status: TextView
    /** What auto-start shows while the last game is read; null once the entry screen or the game is up. */
    private var splash: TextView? = null

    /** The folder each game has remembered, and the parts of the entry screen that show them. Both
     *  games can be pointed out once and either started from here. */
    private val gameUris = HashMap<GameProfile, Uri?>()
    /** Each game's own title art, decoded from the user's copy for its card (never shipped). */
    private val gameArt = HashMap<GameProfile, Bitmap>()
    /** One row card per game on the entry screen: the card (tap to start), the art tile in it, its
     *  three-dot folder menu and its folder line; [cardAdd] is the "add a game" button shown while it
     *  has no folder. */
    private val cardRows = HashMap<GameProfile, LinearLayout>()
    private val cardArt = HashMap<GameProfile, ImageView>()
    private val cardStatus = HashMap<GameProfile, TextView>()
    private val cardAdd = HashMap<GameProfile, Button>()
    /** The game the entry screen last pointed at: what auto-start starts, and the store's default. */
    private var lastGame: GameProfile? = null
    private lateinit var autoStartToggle: CheckBox
    /* Folders that no longer resolve, from the startup probe (see [verifyFolders]): the stored grant
     * outlives the folder, so this is what tells a card its game is not there any more. */
    private val unavailable = HashSet<GameProfile>()
    private val savesMissing = HashSet<GameProfile>()

    private var gameTreeUri: Uri? = null
    /** The saves folder each game has remembered; the game being started picks which one is in use. */
    private val savesUris = HashMap<GameProfile, Uri?>()
    /** The card whose ⋮ launched the save-folder picker (the picker itself carries no game). */
    private var pendingSavesFor: GameProfile? = null
    private var index: GameIndex? = null
    /** Which game the picked folder is, from its entry page; null before the tree is indexed. */
    private var profile: GameProfile? = null
    /* The folder read the moment it was picked, so Start does not walk the tree a second time. */
    private var learnedUri: Uri? = null
    private var learnedIndex: GameIndex? = null
    private var learnedRelease: String? = null
    /** The game's newest release, parsed from the changelog once the tree is indexed (see [GameVersion]). */
    private var gameReleaseVersion: String? = null
    private var fsBridge: FsBridge? = null
    private var saveStore: SaveStore? = null
    private var padLayoutStore: PadLayoutStore? = null
    private var webView: WebView? = null
    private var padView: OnScreenPadView? = null
    private var menuView: SideMenuView? = null
    private var hideWithExternalInput = true
    private var dynamicSticks = true
    private var viewAlign = ViewAlign.DEFAULT
    private var statsEnabled = false
    /** Whether the shim caps the page's frame rate (the side menu's battery switch). */
    private var limitFps = false

    /** The rate that switch stands for, from [FpsLimit]; the slider under it changes this. */
    private var fpsLimit = FpsLimit.DEFAULT
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
        for (p in GameProfile.entries) {
            gameUris[p] = validateGrant(prefs.getString(p.gameFolderKey, null), wantWrite = false)
        }
        /* Folders the previous version kept as one slot (KEY_SAVES) become both games' folder: that is
         * exactly the behaviour they had, and either card can be repointed afterwards. */
        val legacySaves = validateGrant(prefs.getString(KEY_SAVES, null), wantWrite = true)
        if (legacySaves != null) {
            val edit = prefs.edit()
            for (p in GameProfile.entries) edit.putString(p.savesFolderKey, legacySaves.toString())
            edit.remove(KEY_SAVES).apply()
        }
        for (p in GameProfile.entries) {
            savesUris[p] = validateGrant(prefs.getString(p.savesFolderKey, null), wantWrite = true)
        }
        lastGame = prefs.getString(KEY_LAST_GAME, null)
            ?.let { id -> GameProfile.entries.firstOrNull { it.id == id } }
        hideWithExternalInput = prefs.getBoolean(KEY_HIDE_EXTERNAL_INPUT, true)
        dynamicSticks = prefs.getBoolean(KEY_DYNAMIC_STICKS, true)
        viewAlign = ViewAlign.fromWire(prefs.getString(KEY_VIEW_ALIGN, null))
        statsEnabled = prefs.getBoolean(KEY_STATS, false)
        limitFps = prefs.getBoolean(KEY_LIMIT_FPS, false)
        fpsLimit = FpsLimit.normalize(prefs.getInt(KEY_FPS_LIMIT, FpsLimit.DEFAULT))
        logToSaves = prefs.getBoolean(LogFile.PREF_KEY, true)
        /* The store is opened here and not at START, and the previous session's record is read back
         * into the ring: a user who freezes and restarts expects the panel (and the file) to still
         * show what happened, not an empty record from the launch that follows. */
        saveStore = openSaveStore(lastGame)
        carryPreviousRecord(saveStore!!)
        /* "Start last game directly" goes straight into the game, so the entry screen is never built
         * and cannot flash. Anything that stops the game actually starting builds it then, with the
         * reason (see [showPreGame]); a stale grant leaves gameUris[last] null and it is built here. */
        /* A shortcut names the game it wants and wins over "start last game directly"; a plain launch
         * keeps the existing behaviour. */
        val wanted = gameFromIntent(intent) ?: lastGame?.takeIf {
            prefs.getBoolean(KEY_AUTO_START, false)
        }
        val autoStart = wanted?.takeIf { gameUris[it] != null }
        if (autoStart == null) {
            buildPreGameUi()
            /* A folder an earlier version remembered (single-slot, before the port ran two games):
             * read it once to find out which game it is and file it under that game's card. */
            val legacy = validateGrant(prefs.getString(KEY_GAME, null), wantWrite = false)
            if (legacy != null && gameUris.values.all { it == null }) {
                learnGameFolder(legacy, forgetLegacyKey = true)
            }
        } else {
            /* A content view must exist before [applyImmersive] touches the window's decor. */
            showSplash(autoStart)
        }
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
        autoStart?.let {
            diag.line("auto-start: ${it.displayName}")
            startGame(it)
        }
    }

    /* ---------------------------------------------------------------- pre-game screen ---- */

    /**
     * Builds the entry screen on demand: "start last game directly" skips it entirely, so every path
     * that stops the game from actually starting calls this first and then says why on it.
     */
    private fun showPreGame() {
        if (!::status.isInitialized) buildPreGameUi()
    }

    /** What auto-start shows while the last game is read, so the wait is not a black window. */
    private fun showSplash(game: GameProfile) {
        val view = TextView(this).apply {
            text = "Starting ${game.displayName}\u2026"
            textSize = 15f
            setTextColor(PortStyle.DIM)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
        }
        splash = view
        setContentView(view, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    /**
     * What to say when a picked folder carries no game: one sentence naming the roots the port looks
     * for. (The per-game hints used to be concatenated, which read "…terra/ directory. or Pick…".)
     */
    private fun notAGame(): String =
        "That folder is not a game this port runs \u2014 pick the folder holding " +
            GameProfile.entries.joinToString(" or ") { "${it.displayName}'s ${it.pageRoot}/" } + "."

    /** The ⋮ Help: what the computer's copy looks like, and where its saves are. */
    private fun showHelp(profile: GameProfile) {
        diag.line("help opened for ${profile.displayName}")
        AlertDialog.Builder(this)
            .setTitle(profile.displayName)
            .setMessage(helpText(profile))
            .setPositiveButton("OK", null)
            .show()
    }

    private fun helpText(profile: GameProfile): String {
        val saves = profile.pcSaves.joinToString("\n") { "  \u2022 $it" }
        return "Point the port at your own copy of the game: no game files are bundled and nothing " +
            "is uploaded.\n\n" +
            "Game files\n" +
            "On Steam: right-click ${profile.displayName} \u2192 Manage \u2192 Browse local files. " +
            "Copy the ${profile.pageRoot}/ folder from ${profile.pcInstallFolder} to the phone, then " +
            "pick the folder that holds it. Only ${profile.pageRoot}/ is read; the rest of the " +
            "install is the desktop NW.js runtime, not needed.\n\n" +
            "Saves\n" +
            "On the computer they are in:\n$saves\n" +
            "Copy the save folder itself into the saves folder you picked here (this card's \u22ee " +
            "\u2192 Select save folder), or just its contents \u2014 the port reads either shape " +
            "(the game's own ${profile.saveSubdir}/ subfolder, or the files at the top level).\n" +
            "Not there? Search your computer for ${profile.pcSaveFiles.joinToString(" or ")}."
    }

    /**
     * Checks, off the main thread, that the remembered folders still resolve. A persisted grant
     * outlives the folder it points at — a moved or deleted folder keeps it on Android 16 — so
     * without this the cards claim folders that are not there and the saves store is opened on a tree
     * that fails on every write. [refreshUi] marks what this finds; [openSaveStore] acts on it.
     */
    private fun verifyFolders() {
        val games = gameUris.toMap()
        val saves = savesUris.toMap()
        Thread({
            val noGame = games.filter { (p, uri) ->
                uri != null && !GameFiles.existsByPath(contentResolver, uri, p.indexHtml)
            }.keys
            val noSaves = saves.filter { (_, uri) ->
                uri != null && !GameFiles.treeResolves(contentResolver, uri)
            }.keys
            runOnUiThread {
                unavailable.clear()
                unavailable.addAll(noGame)
                savesMissing.clear()
                savesMissing.addAll(noSaves)
                val notes = ArrayList<String>()
                if (noGame.isNotEmpty()) {
                    notes.add(noGame.joinToString(", ") { it.displayName } +
                        ": game folder missing \u2014 pick it again from the card's \u22ee menu")
                    diag.line("game folders no longer resolve: ${noGame.joinToString { it.id }}")
                }
                if (noSaves.isNotEmpty()) {
                    notes.add(noSaves.joinToString(", ") { it.displayName } +
                        ": saves folder missing \u2014 saves are kept inside the app")
                    diag.line("saves folders no longer resolve: ${noSaves.joinToString { it.id }}")
                }
                if (notes.isNotEmpty()) setStatus(notes.joinToString(". ") + ".")
                refreshUi()
            }
        }, "ada-probe").start()
    }

    /**
     * The entry screen: one row card per game — its title art in a tile, its name and folders, a play
     * badge and a three-dot folder menu, tap to start — or an "add" button while it has no folder,
     * then the "start last game directly" switch, the status and the version.
     *
     * The three-dot menu points the card at that game's install or saves folder. A picked folder is
     * filed under whichever game it turns out to be (see [learnGameFolder]), so a wrong card's pick
     * fixes itself.
     */
    private fun buildPreGameUi() {
        val pad = PortStyle.dp(this, 20)
        val gap = PortStyle.dp(this, 10)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.BLACK)
        }

        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    TextView(this@PortActivity).apply {
                        text = getString(R.string.app_name)
                        textSize = 18f
                        setTextColor(PortStyle.TEXT)
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                /* A device-only failure is reported from here: the record names the WebView, the GL
                 * backend and what the engine was doing, without adb. The panel it opens is also
                 * where the stored Resolution can be dropped (FINDINGS §19). */
                addView(Button(this@PortActivity).apply {
                    text = "Troubleshoot"
                    setOnClickListener { showDiagnostics() }
                    PortStyle.dress(this)
                })
            }
        )

        for (p in GameProfile.entries) addGameSection(root, p)

        autoStartToggle = CheckBox(this).apply {
            text = "Start last game directly"
            textSize = 14f
            setTextColor(PortStyle.TEXT)
            buttonTintList = ColorStateList.valueOf(PortStyle.TEXT)
            isChecked = prefs.getBoolean(KEY_AUTO_START, false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(KEY_AUTO_START, checked).apply()
            }
        }
        root.addView(autoStartToggle, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = gap })

        status = TextView(this).apply {
            textSize = 13f
            setTextColor(PortStyle.DIM)
            setPadding(0, gap, 0, 0)
        }
        root.addView(status)
        /* Which build the user is on, on the screen every report is taken from. */
        root.addView(
            TextView(this).apply {
                text = "port ${appVersion()}"
                textSize = 12f
                setTextColor(PortStyle.DIM)
                setPadding(0, gap, 0, 0)
            }
        )
        /* A phone in landscape is shorter than two cards: the screen scrolls rather than clipping the
         * checkbox and the status lines. */
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
        splash = null
        refreshUi()
        loadGameArt()
        verifyFolders()
    }

    /**
     * One game's slot on the entry screen: a row card — its title art in a square tile, the game's
     * name and folders beside it, a play badge and a three-dot folder menu, tap anywhere to start
     * (see [cardAction]) — or, while it has no folder yet, an "add" button that points the card at
     * one. [refreshUi] shows whichever the game's state calls for.
     */
    private fun addGameSection(parent: LinearLayout, profile: GameProfile) {
        val gap = PortStyle.dp(this, 10)
        val inset = PortStyle.dp(this, 8)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = PortStyle.card(this@PortActivity)
            isClickable = true
            setOnClickListener { cardAction(profile) }
            setPadding(inset, inset, inset, inset)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = gap }
        }
        val tile = FrameLayout(this).apply {
            background = PortStyle.tile(this@PortActivity)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                PortStyle.dp(this@PortActivity, TILE_DP), PortStyle.dp(this@PortActivity, TILE_DP)
            )
        }
        val art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        tile.addView(art)
        card.addView(tile)

        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = PortStyle.dp(this@PortActivity, 16) }
        }
        meta.addView(TextView(this).apply {
            text = profile.displayName
            textSize = 16f
            setTextColor(PortStyle.TEXT)
        })
        val folder = TextView(this).apply {
            textSize = 11f
            setTextColor(PortStyle.DIM)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.15f)
            setPadding(0, PortStyle.dp(this@PortActivity, 4), 0, 0)
        }
        meta.addView(folder)
        card.addView(meta)

        /* The two controls, stacked: the ⋮ opens the folder menu, the play badge beneath it is an
         * indicator only (a tap on it falls through to the card, which starts the game). */
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        actions.addView(TextView(this).apply {
            text = "\u22ee"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(PortStyle.TEXT)
            isClickable = true
            setOnClickListener { showGameMenu(it, profile) }
            layoutParams = LinearLayout.LayoutParams(
                PortStyle.dp(this@PortActivity, DOTS_DP), PortStyle.dp(this@PortActivity, DOTS_DP)
            )
        })
        actions.addView(ImageButton(this).apply {
            isClickable = false
            background = PortStyle.badge(this@PortActivity)
            imageTintList = ColorStateList.valueOf(PortStyle.INK)
            setImageResource(R.drawable.ic_play)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val playInset = PortStyle.dp(this@PortActivity, 10)
            setPadding(playInset, playInset, playInset, playInset)
            layoutParams = LinearLayout.LayoutParams(
                PortStyle.dp(this@PortActivity, PLAY_DP), PortStyle.dp(this@PortActivity, PLAY_DP)
            ).apply { topMargin = PortStyle.dp(this@PortActivity, 4) }
        })
        card.addView(actions)

        val add = Button(this).apply {
            text = "Add ${profile.displayName}"
            setOnClickListener { pick(REQ_GAME, wantWrite = false) }
            PortStyle.dress(this)
            setCompoundDrawablePadding(PortStyle.dp(this@PortActivity, 8))
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_add, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = gap }
        }
        parent.addView(card)
        parent.addView(add)
        cardRows[profile] = card
        cardArt[profile] = art
        cardStatus[profile] = folder
        cardAdd[profile] = add
    }

    /** A card's one action: start its game, or pick that game's folder when it has none yet (or the
     *  one it had is no longer there). */
    private fun cardAction(profile: GameProfile) {
        if (gameUris[profile] == null || unavailable.contains(profile)) {
            pick(REQ_GAME, wantWrite = false)
            return
        }
        startGame(profile)
    }

    /** The game a shortcut asks for, or null for a plain launch. */
    private fun gameFromIntent(intent: Intent?): GameProfile? =
        intent?.getStringExtra(EXTRA_GAME)
            ?.let { id -> GameProfile.entries.firstOrNull { it.id == id } }

    /** The intent a shortcut for [profile] carries: this activity, naming that game. */
    private fun shortcutIntent(profile: GameProfile): Intent =
        Intent(this, PortActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(EXTRA_GAME, profile.id)

    /** The ⋮ item: ask the launcher to put an icon for [profile] on the home screen. */
    private fun pinShortcut(profile: GameProfile) {
        val manager = getSystemService(ShortcutManager::class.java)
        if (manager == null || !manager.isRequestPinShortcutSupported) {
            setStatus("This launcher does not allow home-screen shortcuts.")
            return
        }
        val info = ShortcutInfo.Builder(this, "game-" + profile.id)
            .setShortLabel(profile.displayName)
            .setLongLabel("Play ${profile.displayName}")
            .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
            .setIntent(shortcutIntent(profile))
            .build()
        val asked = manager.requestPinShortcut(info, null)
        diag.line("home-screen link for ${profile.displayName}: " +
            (if (asked) "asked" else "refused"))
        if (!asked) setStatus("The home-screen link could not be created.")
    }

    /** A card's menu: point it at that game's install folder, or at that game's saves folder. */
    private fun showGameMenu(anchor: View, profile: GameProfile) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, MENU_SELECT_GAME, 0, "Select game folder")
        menu.menu.add(0, MENU_SELECT_SAVES, 1, "Select save folder")
        menu.menu.add(0, MENU_HELP, 2, "Help: what to copy")
        menu.menu.add(0, MENU_LINK, 3, "Create a home-screen link")
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                MENU_SELECT_GAME -> pick(REQ_GAME, wantWrite = false)
                MENU_SELECT_SAVES -> pick(REQ_SAVES, wantWrite = true, forGame = profile)
                MENU_HELP -> showHelp(profile)
                MENU_LINK -> pinShortcut(profile)
            }
            true
        }
        menu.show()
    }

    /**
     * Reads each remembered game's own art out of the user's copy and decodes it for that game's
     * card. Off the main thread: this is game art drawn from their installation, never shipped in
     * the APK.
     */
    private fun loadGameArt() {
        for ((profile, uri) in gameUris.toMap()) {
            if (uri == null) continue
            Thread({
                val bitmap = decodeArt(uri, profile.artPath)
                if (bitmap != null) runOnUiThread {
                    gameArt[profile] = bitmap
                    refreshUi()
                }
            }, "ada-art").start()
        }
    }

    /**
     * The game's title art, decoded at roughly the card tile's size (a power-of-two downscale, so a
     * big image does not cost a full-size bitmap) and cropped square, because the tile is square and
     * the games' art is not (CrossCode's is 3:2). Null when the user's copy does not carry that file,
     * which is not a failure: the card then shows the port's own mark.
     */
    private fun decodeArt(tree: Uri, path: String): Bitmap? {
        val bytes = GameFiles.readByPath(contentResolver, tree, path) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val target = PortStyle.dp(this, TILE_DP) * 2
        while (bounds.outHeight / (sample * 2) >= target) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null
        return squareCrop(decoded)
    }

    /** The largest centred square of [src]: the art is shown in a square tile, so the crop is here. */
    private fun squareCrop(src: Bitmap): Bitmap {
        val side = minOf(src.width, src.height)
        if (side == src.width && side == src.height) return src
        val cropped = Bitmap.createBitmap(
            src, (src.width - side) / 2, (src.height - side) / 2, side, side
        )
        src.recycle()
        return cropped
    }

    private fun refreshUi() {
        for (p in GameProfile.entries) {
            val ready = gameUris[p] != null
            val gone = unavailable.contains(p)
            val art = gameArt[p]
            if (art != null) cardArt[p]?.setImageBitmap(art)
            else cardArt[p]?.setImageResource(R.drawable.ic_launcher_foreground)
            /* The card is shown once the game has a folder; before that its "add" button stands in. */
            cardRows[p]?.visibility = if (ready) View.VISIBLE else View.GONE
            cardAdd[p]?.visibility = if (ready) View.GONE else View.VISIBLE
            /* A folder that no longer resolves is dimmed with its play badge: a tap re-opens the
             * picker rather than starting a game that is not there (see [cardAction]). */
            cardRows[p]?.alpha = if (gone) 0.5f else 1f
            cardStatus[p]?.text = "files: " + (GameFiles.pathOf(gameUris[p]) ?: "(not set)") +
                (if (gone) " (missing)" else "") +
                "\n" + "saves: " + (GameFiles.pathOf(savesUris[p]) ?: "(not set)") +
                (if (savesMissing.contains(p)) " (missing)" else "")
        }
        if (status.length() == 0) {
            status.text = when {
                gameUris.values.all { it == null } ->
                    "Pick a game's install folder \u2014 the one holding Alabaster Dawn's terra/ or CrossCode's assets/."
                savesUris.values.all { it == null } ->
                    "No saves folder: saves will be kept inside the app and cannot be copied out."
                else -> "Ready."
            }
        }
    }

    /** While a game is starting, no card is tappable. */
    private fun setCardsEnabled(enabled: Boolean) {
        cardRows.values.forEach { it.isEnabled = enabled }
    }

    private fun pick(requestCode: Int, wantWrite: Boolean, forGame: GameProfile? = null) {
        if (requestCode == REQ_SAVES) pendingSavesFor = forGame
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
            val target = pendingSavesFor
            pendingSavesFor = null
            if (target == null) {
                diag.line("saves folder picked with no game attached; ignored")
                return
            }
            savesUris[target] = uri
            savesMissing.remove(target)
            prefs.edit().putString(target.savesFolderKey, uri.toString()).apply()
            /* The store was opened for another game (or app storage) at startup: follow the folder the
             * user just pointed at, and read its previous record before a flush can overwrite it. */
            val fresh = openSaveStore(target)
            saveStore = fresh
            if (!previousRecordCarried) carryPreviousRecord(fresh)
            /* Saves made while app storage was in use would otherwise be left behind, unreachable. */
            carryAppStorageSaves(uri, target)
            diag.line("granted saves for ${target.displayName}: $uri (flags=$flags)")
        } else {
            /* Read it now: reading is what says which game it is, and Start then does not walk the
             * tree again (see [startGame]). */
            learnGameFolder(uri)
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

    /**
     * Reads a folder the user just picked and files it under whichever game it is, so the entry
     * screen learns both folders from two picks and either can then be started with one tap. The
     * index is kept, because Start needs exactly this map and reading it twice buys nothing.
     */
    private fun learnGameFolder(uri: Uri, forgetLegacyKey: Boolean = false) {
        setStatus("Reading " + (GameFiles.displayNameOf(uri) ?: "the folder") + "…")
        Thread({
            val built = try {
                GameFiles.indexTree(contentResolver, uri)
            } catch (e: Exception) {
                Log.e(TAG, "indexing failed", e)
                diag.line("indexing failed: $e")
                null
            }
            val detected = built?.let(GameProfile::detect)
            val release = built?.let { raw ->
                detected?.versionPath?.let {
                    GameFiles.readText(contentResolver, uri, raw, it)?.let(GameVersion::fromChangelog)
                }
            }
            val art = if (detected != null) decodeArt(uri, detected.artPath) else null
            if (built != null && detected != null) GameIndexCache.save(this, uri, release, built)
            runOnUiThread {
                if (built == null) {
                    status.text = "Could not read that folder."
                    return@runOnUiThread
                }
                if (detected == null) {
                    setStatus(
                        if (built.size == 0) {
                            "That folder is empty \u2014 the game is not there any more. Pick it again."
                        } else {
                            notAGame()
                        }
                    )
                    diag.line("picked folder carries no game (${built.size} entries)")
                    return@runOnUiThread
                }
                val edit = prefs.edit().putString(detected.gameFolderKey, uri.toString())
                if (forgetLegacyKey) edit.remove(KEY_GAME)
                edit.apply()
                gameUris[detected] = uri
                unavailable.remove(detected)
                if (art != null) {
                    gameArt[detected] = art
                }
                lastGame = detected
                gameTreeUri = uri
                learnedUri = uri
                learnedIndex = built
                learnedRelease = release
                diag.line("${detected.displayName} files: ${GameFiles.displayNameOf(uri)} " +
                    "(${built.size} entries)")
                status.text = ""
                refreshUi()
            }
        }, "ada-index").start()
    }

    /** Starts [selected] from the folder its card holds, reading the folder only if it was not. */
    private fun startGame(selected: GameProfile) {
        val tree = gameUris[selected] ?: return
        if (index != null) return
        lastGame = selected
        prefs.edit().putString(KEY_LAST_GAME, selected.id).apply()
        /* Each game has its own saves folder: the store (log file, pad layout, the game's own saves)
         * follows the game being started. This session's earlier lines are already in the ring, so the
         * record is not re-carried. */
        saveStore = openSaveStore(selected)
        setCardsEnabled(false)
        /* The folder was read when it was picked; reuse that map rather than walking the tree again. */
        if (learnedUri == tree && learnedIndex != null) {
            onFolderRead(selected, tree, learnedIndex, learnedRelease, System.currentTimeMillis())
            return
        }
        /* No walk yet: the index from the last launch, if it still describes this folder and this
         * build (see GameIndexCache). The version file is read by name, so this needs no index. */
        val version = readVersion(tree, selected)
        val cached = GameIndexCache.load(this, tree, version)
        if (cached != null) {
            onFolderRead(selected, tree, cached, version, System.currentTimeMillis())
            return
        }
        setStatus("Indexing game files…")
        /* The flush chain starts here rather than in launchWebView, because reading a big game folder
         * can take a while and a failure there has to leave a record on disk, not only on screen.
         * launchWebView re-posts this same chain. */
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
            val detected = built?.let(GameProfile::detect)
            val release = built?.let { raw ->
                detected?.versionPath?.let {
                    GameFiles.readText(contentResolver, tree, raw, it)?.let(GameVersion::fromChangelog)
                }
            }
            if (built != null && detected != null) GameIndexCache.save(this, tree, release, built)
            runOnUiThread { onFolderRead(selected, tree, built, release, started) }
        }, "ada-index").start()
    }

    /**
     * The folder is read: launch it if it is the game whose Start was tapped, and otherwise say which
     * game it actually is (and file it under that one's row, which is what the user has to tap next).
     */
    private fun onFolderRead(
        selected: GameProfile,
        tree: Uri,
        built: GameIndex?,
        release: String?,
        started: Long,
    ) {
        if (built == null) {
            showPreGame()
            setStatus("Could not read that folder.")
            setCardsEnabled(true)
            return
        }
        val detected = GameProfile.detect(built)
        if (detected == null) {
            showPreGame()
            setStatus(
                if (built.size == 0) {
                    "That folder is empty \u2014 the game is not there any more. Pick it again."
                } else {
                    notAGame()
                }
            )
            diag.line("picked folder carries no game (${built.size} entries)")
            setCardsEnabled(true)
            return
        }
        if (detected != selected) {
            showPreGame()
            prefs.edit().putString(detected.gameFolderKey, tree.toString()).apply()
            gameUris[detected] = tree
            setStatus("That folder is ${detected.displayName}, not ${selected.displayName}.")
            diag.line("Start tapped for ${selected.displayName} with a ${detected.displayName} folder")
            setCardsEnabled(true)
            refreshUi()
            return
        }
        val bound = built.bind(detected.pageRoot)
        profile = detected
        index = bound
        gameTreeUri = tree
        gameReleaseVersion = release
        diag.line("indexed ${bound.size} entries in ${System.currentTimeMillis() - started}ms " +
            "(${detected.displayName})")
        padLayoutStore = PadLayoutStore(prefs, saveStore!!)
        fsBridge = FsBridge(contentResolver, tree, bound, saveStore!!)
        setStatus("Loaded ${bound.size} files.")
        launchWebView()
    }

    /** The game's own version, read by path (no index needed): the index cache's validity check. */
    private fun readVersion(tree: Uri, profile: GameProfile): String? {
        val path = profile.versionPath ?: return null
        val bytes = GameFiles.readByPath(contentResolver, tree, path) ?: return null
        return GameVersion.fromChangelog(String(bytes, Charsets.UTF_8))
    }

    private fun openSaveStore(forGame: GameProfile? = null): SaveStore {
        val uri = savesUris[forGame]
        /* A stored grant outlives the folder it points at, and a SafStore on a dead tree fails on
         * every write instead of saying so: the tree is checked first, and a folder that is gone
         * falls back to app storage, where writes work (the entry screen marks the folder missing). */
        if (uri != null && GameFiles.treeResolves(contentResolver, uri)) {
            try {
                val saf = SafStore(contentResolver, uri)
                /* A folder holding the game's own subfolder (`Saves/…`, `Default/…`) is served as
                 * is; one holding that subfolder's *contents* — the desktop folder copied flat —
                 * is served at the root instead (see [SaveLayout]). */
                return if (forGame != null) SaveLayout.detect(saf, forGame) else saf
            } catch (e: Exception) {
                Log.e(TAG, "saves tree unusable, falling back to app storage", e)
            }
        }
        Log.w(TAG, "no usable saves folder: saving into app storage (not exportable)")
        return FileStore.appPrivate(this)
    }

    /**
     * Carries the app-storage saves into a folder the user just picked.
     *
     * The two stores are alternatives, not a chain (see [openSaveStore]): saves made while no folder
     * was set — or while the set one was missing — live in app storage, and switching to a folder
     * would leave them behind, unreachable. App storage is only ever this port's own fallback, never
     * a folder the user chose, so copying **out of it** is always safe; two user folders are never
     * merged, because that would overwrite a newer save with an older one.
     *
     * A file is copied when the folder does not have it, or the app's copy is newer. Nothing is
     * deleted, and the copy runs off the main thread (the target is SAF).
     */
    private fun carryAppStorageSaves(into: Uri, forGame: GameProfile) {
        val source = FileStore.appPrivate(this)
        Thread({
            val copied = try {
                SaveCarry.copyNewer(source, SaveLayout.detect(SafStore(contentResolver, into), forGame))
            } catch (e: Exception) {
                Log.e(TAG, "cannot carry app-storage saves into ${GameFiles.pathOf(into)}", e)
                return@Thread
            }
            if (copied.isEmpty()) return@Thread
            runOnUiThread {
                diag.line("carried ${copied.size} file(s) from app storage into " +
                    "${GameFiles.pathOf(into)}: ${copied.joinToString(", ")}")
                setStatus("Copied ${copied.size} save file(s) from app storage into " +
                    "${GameFiles.pathOf(into) ?: "the folder"}.")
            }
        }, "ada-saves").start()
    }

    /**
     * Drops the game's stored Resolution option — the one value a phone user can set that leaves the
     * game too slow to reach its own Options menu. The engine keeps its device-local options as one
     * JSON object in `localStorage` and reads it at boot, so the reset is a URL parameter the shim
     * acts on before the engine boots (the profile's `resetVideoParam`); with a page to reload it
     * happens now, and from the pre-game screen it is armed for the next start instead. The one-shot
     * is cleared as the URL is built, so a kill in between cannot leave it half-applied.
     *
     * Only a game whose profile carries a reset parameter has the control at all (the side menu and
     * diagnostics hide it for the others).
     */
    private fun resetVideoOptions() {
        if (profile?.resetVideoParam == null) return
        closeMenu()
        val view = webView
        if (view == null) {
            prefs.edit().putBoolean(KEY_RESET_VIDEO, true).apply()
            status.text = "The game's Resolution will be reset when it starts."
            diag.line("the game's stored Resolution will be reset at the next start")
            return
        }
        prefs.edit().putBoolean(KEY_RESET_VIDEO, false).apply()
        diag.line("reloading to reset the game's stored Resolution")
        view.loadUrl(gameUrl(resetVideo = true))
    }

    /** The game's URL, carrying the one-shot video reset to the shim when one was armed. */
    private fun gameUrl(resetVideo: Boolean): String {
        val p = profile ?: return ""
        val param = p.resetVideoParam
        return if (resetVideo && param != null) "${p.entryUrl}?$param=1" else p.entryUrl
    }

    private fun launchWebView() {
        val built = index ?: return
        val bridge = fsBridge ?: return
        val selected = profile ?: return
        shimSource = readAsset(selected.shimAsset)
        val injectShim = !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        diag.line("webView ${webViewPackage()} document-start scripts=" + !injectShim +
            " ua=${webSettingsUserAgent()}")
        if (injectShim) {
            diag.line("document-start scripts unsupported; the shim is injected via " + selected.indexHtml)
        }
        val handler = GameAssetHandler(
            contentResolver, gameTreeUri!!, built, selected, assets, injectShim, diag
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
        /* WebView inspection: always for a debug build, and for a released APK only when the global
         * setting below is on (`adb shell settings put global rfport_webview_debug 1`). A released
         * build is not debuggable, and this is the only way to read a page's own exception - a stalled
         * loader leaves nothing else. Reading a global setting needs no permission; writing it is the
         * shell's. */
        if (webViewInspectable()) {
            WebView.setWebContentsDebuggingEnabled(true)
            diag.line("webView inspection on (${DEBUG_WEBVIEW_SETTING})")
        }
        view.setBackgroundColor(Color.BLACK)
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        val telemetry = Telemetry(this)
        view.addJavascriptInterface(
            PortBridge(
                bridge,
                viewAlign = { viewAlign },
                statsEnabled = { statsEnabled },
                fpsLimit = { if (limitFps) fpsLimit else FpsLimit.OFF },
                telemetry = telemetry,
                diag = diag,
                onQuit = { runOnUiThread { exitGame() } },
            ).also { port ->
                /* The page's shader self-test finishes asynchronously; show the record again once it
                 * carries the verdicts (FINDINGS §22.11). */
                port.onShaderSelfTestDone = { runOnUiThread { if (!isFinishing) showDiagnostics() } }
            },
            BRIDGE_NAME
        )
        if (!injectShim) {
            try {
                WebViewCompat.addDocumentStartJavaScript(view, shimSource, setOf(GameProfile.ORIGIN))
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
            hideWithExternalInput = this@PortActivity.hideWithExternalInput
            dynamicSticks = this@PortActivity.dynamicSticks
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
        val menu = SideMenuView(this, viewAlignSupported = selected.viewAlign).apply {
            setHideWithExternalInput(this@PortActivity.hideWithExternalInput)
            setDynamicSticks(this@PortActivity.dynamicSticks)
            setStatsEnabled(statsEnabled)
            setLimitFps(limitFps)
            setFpsLimit(fpsLimit)
            setLogToSaves(logToSaves)
            setAlign(viewAlign)
            onHideWithExternalInput = {
                this@PortActivity.hideWithExternalInput = it
                prefs.edit().putBoolean(KEY_HIDE_EXTERNAL_INPUT, it).apply()
                padView?.hideWithExternalInput = it
            }
            onDynamicSticks = {
                this@PortActivity.dynamicSticks = it
                prefs.edit().putBoolean(KEY_DYNAMIC_STICKS, it).apply()
                padView?.dynamicSticks = it
                diag.line("dynamic sticks " + (if (it) "on" else "off"))
            }
            onStatsEnabled = {
                this@PortActivity.statsEnabled = it
                prefs.edit().putBoolean(KEY_STATS, it).apply()
            }
            /* The shim reads this on its once-per-frame poll, so the cap lands on the next frame.
             * The menu is told too: the rate slider under the switch is shown only while the cap is
             * on, and that is this view's own state to keep. */
            onLimitFps = {
                this@PortActivity.limitFps = it
                prefs.edit().putBoolean(KEY_LIMIT_FPS, it).apply()
                menuView?.setLimitFps(it)
                diag.line("frame limit " + (if (it) "on: $fpsLimit fps" else "off"))
            }
            /* The slider's rate; the switch keeps its own state, so a rate can be chosen while the
             * cap is off and is what the cap uses the moment it goes on. */
            onFpsLimit = {
                this@PortActivity.fpsLimit = it
                prefs.edit().putInt(KEY_FPS_LIMIT, it).apply()
                menuView?.setFpsLimit(it)
                diag.line("frame limit: $it fps" + (if (this@PortActivity.limitFps) "" else " (off)"))
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
            onExit = { exitGame() }
            onGameSelection = { backToSelection() }
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
        splash = null
        view.requestFocus()
        setStatus("Loading the game…")
        diag.line("loading ${selected.entryUrl}")
        webViewStartedAt = monotonicMs()
        watchdog.removeCallbacksAndMessages(null)
        watchdog.postDelayed(watchdogTick, WATCHDOG_TICK_MS)
        val resetVideo = selected.resetVideoParam != null && prefs.getBoolean(KEY_RESET_VIDEO, false)
        if (resetVideo) prefs.edit().putBoolean(KEY_RESET_VIDEO, false).apply()
        view.loadUrl(gameUrl(resetVideo))
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
        teardownWebView()
        if (index != null && fsBridge != null) launchWebView()
    }

    /**
     * The one writer for the pre-game status line. Auto-start never builds the entry screen, so until
     * it does (or the game's WebView replaces it) the text goes to [splash] instead.
     */
    private fun setStatus(text: String) {
        if (::status.isInitialized) status.text = text
        splash?.text = text
        Log.i(TAG, text)
    }

    /* ------------------------------------------------------------- diagnostics ---- */

    private fun showDiagnostics() {
        diag.line("diagnostics opened")
        val text = diag.snapshot(diagFacts())
        DiagnosticsDialog(
            this,
            text,
            { shareDiagnostics(text) },
            { resetVideoOptions() },
            { runShaderSelfTest() },
            { openDriverSettings() },
            shaderTools = profile?.rewrites == true,
        ).apply {
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

    /**
     * Asks the page to run the shader self-test (FINDINGS §22.11): every spelling the port could serve,
     * compiled on this device's own GL front end. The verdicts arrive as record lines, and the summary
     * brings this panel back with them in it.
     */
    private fun runShaderSelfTest() {
        val page = webView
        if (page == null) {
            diag.line("shader self-test: no page to ask")
            return
        }
        diag.line("shader self-test: asked")
        page.evaluateJavascript("window.adaShaderSelfTest && window.adaShaderSelfTest()", null)
    }

    /**
     * Where the driver choice lives: ANGLE Preferences — the ANGLE apk ships it as a Developer-options
     * entry, under either of its two package names — with Developer options as the fallback. The port
     * cannot set the choice itself (it is a privileged `Settings.Global` entry, FINDINGS §22.8), so the
     * most it can do is put the screen in front of the user.
     */
    private fun openDriverSettings() {
        for (component in ANGLE_PREFERENCES) {
            val intent = Intent(Intent.ACTION_MAIN).setComponent(component)
            if (packageManager.resolveActivity(intent, 0) == null) continue
            try {
                startActivity(intent)
                diag.line("opened ${component.flattenToShortString()}")
                return
            } catch (e: Exception) {
                Log.w(TAG, "cannot open $component", e)
            }
        }
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            diag.line("opened Developer options: no ANGLE Preferences on this device")
        } catch (e: Exception) {
            diag.line("no driver settings screen: ${e.javaClass.simpleName}")
        }
    }

    /** What the record is read against: the device, the WebView, the folders and the live counters. */
    private fun diagFacts(): List<String> = listOf(
        "app ${appVersion()} on ${Build.MANUFACTURER} ${Build.MODEL}, Android " +
            "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}, ${Build.SUPPORTED_ABIS.firstOrNull()})",
        "game ${assetHandler?.gameBuild() ?: gameReleaseVersion ?: "unknown"}",
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
                        putExtra(
                            Intent.EXTRA_SUBJECT,
                            (profile?.displayName ?: "Radical Fish") + " Android port diagnostics"
                        )
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
        menu.setStatus(padView?.externalInputInUse == true, savesStatusLine())
        /* The game's name matters in a report from a host that runs two of them. */
        menu.setVersion("port ${appVersion()} · " + (profile?.displayName ?: "no game"))
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
        savesUris[profile]?.let { GameFiles.pathOf(it) } ?: "app storage (not exportable)"

    /* ------------------------------------------------------------------------- input ---- */

    /**
     * Back belongs to the port - it opens the menu while the game runs and exits the app before it
     * does - and it is taken here rather than left to the framework so that **every** Back reaches it:
     * a key event goes through the activity before the WebView can hand it to the page, which is what a
     * device report of "the physical Back button does not open the menu" (an AYN Odin 3) looks like from
     * the inside. `onBackInvokedDispatcher` (registered in `onCreate`) covers the gesture on API 33+,
     * and this covers the key on every version. Each path says which one it was in the record, so a
     * report like that can be told apart from a Back that never arrived at all.
     *
     * The two paths must not both act on one press: on API 33+, where the callback is registered, the
     * framework routes the Back key to it as well as the gesture, and handling the key here too ran
     * [handleBack] twice and opened the menu only to close it again. So from 33 up the callback owns
     * Back and the key is only swallowed; below 33 there is no callback and the key is all there is
     * (verified on an API 36 device: one `input keyevent 4` produced both a dispatcher and a key line).
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
                event.action == KeyEvent.ACTION_UP
            ) {
                diag.line("back: key (${if (webView != null) "game" else "pre-game"})")
                handleBack()
            }
            return true
        }
        if (isGamepad(event.source)) {
            padView?.noteExternalInput()
            Gamepad.onKey(event)
            if (event.action == KeyEvent.ACTION_DOWN) {
                Log.d(TAG, "key ${KeyEvent.keyCodeToString(event.keyCode)} -> pad")
            }
            return true
        }
        /* A hardware key the player pressed is the same "external input" as a controller. The volume
         * keys are system keys, not playing, so they must not hide the pad. */
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN &&
            code != KeyEvent.KEYCODE_VOLUME_MUTE
        ) {
            padView?.noteExternalInput()
            if (event.action == KeyEvent.ACTION_DOWN) {
                Log.d(TAG, "key ${KeyEvent.keyCodeToString(event.keyCode)} -> page (scan ${event.scanCode})")
            }
        }
        /* Chromium derives `event.code` from the scan code, and the engine matches its bindings on
         * `code` alone. A key event that carries no scan code therefore reaches the page as
         * `code: ""` and plays nothing, so the port dispatches the DOM event the page would have
         * got; anything the port cannot name as a DOM key is left to the WebView as before. */
        if (event.scanCode == 0) {
            val keyEvent = domKeyEvent(event)
            if (keyEvent != null) {
                webView?.evaluateJavascript(keyEvent, null)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * The `keydown`/`keyup` the page should have received for [event], or null when the Android key
     * code has no DOM name.
     *
     * `code` is the physical key and is what Alabaster Dawn's `terra` engine binds on; `key` is
     * carried because the engine reads it for a few never-ignored keys. Impact-era engines bind on the
     * *legacy* `keyCode`/`which` instead - CrossCode's Cubic Impact 0.5 does - and the
     * `KeyboardEvent` constructor refuses to set those, so they are defined onto the event after it is
     * built. Without them the event reached CrossCode and its title screen ignored it (measured on the
     * S22 Ultra, 2026-10-04: `key KEYCODE_ENTER -> page` in the record, no effect on screen).
     */
    private fun domKeyEvent(event: KeyEvent): String? {
        val (code, key) = when (val k = event.keyCode) {
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ->
                "Key" + ('A' + k - KeyEvent.KEYCODE_A) to ('a' + k - KeyEvent.KEYCODE_A).toString()

            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
                "Digit" + (k - KeyEvent.KEYCODE_0) to (k - KeyEvent.KEYCODE_0).toString()

            KeyEvent.KEYCODE_DPAD_UP -> "ArrowUp" to "ArrowUp"
            KeyEvent.KEYCODE_DPAD_DOWN -> "ArrowDown" to "ArrowDown"
            KeyEvent.KEYCODE_DPAD_LEFT -> "ArrowLeft" to "ArrowLeft"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "ArrowRight" to "ArrowRight"
            KeyEvent.KEYCODE_ENTER -> "Enter" to "Enter"
            KeyEvent.KEYCODE_NUMPAD_ENTER -> "NumpadEnter" to "Enter"
            KeyEvent.KEYCODE_ESCAPE -> "Escape" to "Escape"
            KeyEvent.KEYCODE_SPACE -> "Space" to " "
            KeyEvent.KEYCODE_TAB -> "Tab" to "Tab"
            KeyEvent.KEYCODE_SHIFT_LEFT -> "ShiftLeft" to "Shift"
            KeyEvent.KEYCODE_SHIFT_RIGHT -> "ShiftRight" to "Shift"
            KeyEvent.KEYCODE_CTRL_LEFT -> "ControlLeft" to "Control"
            KeyEvent.KEYCODE_CTRL_RIGHT -> "ControlRight" to "Control"
            KeyEvent.KEYCODE_ALT_LEFT -> "AltLeft" to "Alt"
            KeyEvent.KEYCODE_ALT_RIGHT -> "AltRight" to "Alt"

            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ->
                "F" + (k - KeyEvent.KEYCODE_F1 + 1) to "F" + (k - KeyEvent.KEYCODE_F1 + 1)

            else -> return null
        }
        val type = if (event.action == KeyEvent.ACTION_UP) "keyup" else "keydown"
        val repeat = if (event.repeatCount > 0) "true" else "false"
        val legacy = legacyKeyCode(code)
        val legacyJs = if (legacy > 0) {
            "try{Object.defineProperty(e,'keyCode',{get:function(){return $legacy}});" +
                "Object.defineProperty(e,'which',{get:function(){return $legacy}});}catch(x){}"
        } else {
            ""
        }
        return "(function(){var e=new KeyboardEvent('$type',{code:'$code',key:'$key'," +
            "bubbles:true,cancelable:true,repeat:$repeat});$legacyJs" +
            "window.dispatchEvent(e);})()"
    }

    /**
     * The legacy `keyCode`/`which` a DOM `code` stands for, or 0 when it has none. These are the
     * pre-`code` numbers (`Enter` 13, `Space` 32, arrows 37-40, `KeyA` 65, `Digit0` 48, `F1` 112).
     */
    private fun legacyKeyCode(code: String): Int = when {
        code.length == 4 && code.startsWith("Key") && code[3] in 'A'..'Z' -> code[3].code
        code.startsWith("Digit") && code.length == 6 -> '0'.code + (code[5] - '0')
        code.length in 2..3 && code[0] == 'F' && code.drop(1).toIntOrNull() in 1..12 ->
            111 + code.drop(1).toInt()
        code == "ArrowLeft" -> 37
        code == "ArrowUp" -> 38
        code == "ArrowRight" -> 39
        code == "ArrowDown" -> 40
        code == "Enter" || code == "NumpadEnter" -> 13
        code == "Escape" -> 27
        code == "Space" -> 32
        code == "Tab" -> 9
        code.startsWith("Shift") -> 16
        code.startsWith("Control") -> 17
        code.startsWith("Alt") -> 18
        else -> 0
    }

    /** Mouse movement and clicks hide the pad exactly like a controller; the event is not consumed. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (OnScreenPadView.isPointerInput(event)) padView?.noteExternalInput()
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (isGamepad(event.source)) {
            padView?.noteExternalInput()
            Gamepad.onMotion(event)
            logAxesIfChanged()
            return true
        }
        /* Hover does not reach dispatchTouchEvent, so it is noted here; a click with no preceding
         * movement is noted there instead. */
        if (OnScreenPadView.isPointerInput(event)) padView?.noteExternalInput()
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

    /** A shortcut tapped while the port is already running: switch to the game it names. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val wanted = gameFromIntent(intent) ?: return
        diag.line("shortcut: ${wanted.displayName}")
        if (webView != null) {
            if (wanted == profile) return
            backToSelection()
        }
        if (gameUris[wanted] == null) {
            showPreGame()
            setStatus("${wanted.displayName}: pick its game folder first.")
            return
        }
        startGame(wanted)
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

    /**
     * The single way out of the game: the side menu's Exit and the engine's own quit both land here.
     *
     * The WebView renderer is shared for the life of the app process, and starting the game a second
     * time in the same process was measured to come up black (README "Not in this milestone"); the
     * documented workaround was to swipe the app away from recents. This is that workaround: tear the
     * WebView down, send the task to the back, then end the process, so the next launch is a new
     * process, a new renderer and a clean GL state. `Process.killProcess` is a deliberate exit, not a
     * crash - no dialog.
     *
     * The task is *not* finished. On Android the last activity of a task finishing removes the task
     * from recents (`finishAndRemoveTask` does it explicitly, and plain `finish` does it too), which
     * is why the app disappeared from the task list after an Exit; ending the process alone leaves
     * the recents entry in place, and the system starts a fresh process for it when the user taps it.
     */
    private fun exitGame() {
        diag.line("exit requested")
        flushLogBlocking()
        menuView?.close()
        teardownWebView()
        moveTaskToBack(true)
        Process.killProcess(Process.myPid())
    }

    /**
     * Leaves the game and returns to the entry screen without ending the process. Everything the game
     * owned is dropped ([teardownWebView] plus the per-game state), and the pre-game store is re-opened
     * for the last game's saves folder, so the record keeps landing where the next game's will.
     */
    private fun backToSelection() {
        if (webView == null) return
        diag.line("back to the game selection screen")
        flushLogBlocking()
        menuView?.close()
        teardownWebView()
        watchdog.removeCallbacksAndMessages(null)
        padView = null
        menuView = null
        assetHandler = null
        index = null
        fsBridge = null
        padLayoutStore = null
        profile = null
        engineActive = false
        saveStore = openSaveStore(lastGame)
        buildPreGameUi()
    }

    /** The last record before the process ends: [writeLogAsync]'s thread would not survive the kill. */
    private fun flushLogBlocking() {
        if (!previousRecordRead || !logToSaves || !logDirty) return
        val store = saveStore ?: return
        logDirty = false
        try {
            store.write(LogFile.FILE, diag.snapshot(diagFacts()))
        } catch (e: Exception) {
            Log.w(TAG, "final ${LogFile.FILE} write failed", e)
        }
    }

    /**
     * The one WebView teardown. `destroy()` is only valid once the view has left the view tree, which
     * the old `onDestroy` did not do - it destroyed the WebView while it was still the content view.
     */
    private fun teardownWebView() {
        val view = webView ?: return
        webView = null
        try {
            view.stopLoading()
            view.removeJavascriptInterface(BRIDGE_NAME)
            view.loadUrl("about:blank")
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "WebView teardown failed", e)
        }
    }

    override fun onDestroy() {
        watchdog.removeCallbacksAndMessages(null)
        teardownWebView()
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

    private fun webViewInspectable(): Boolean = isDebuggable() || try {
        Settings.Global.getInt(contentResolver, DEBUG_WEBVIEW_SETTING, 0) != 0
    } catch (e: Exception) {
        false
    }

    private fun readAsset(name: String): String = try {
        assets.open(name).use { String(it.readBytes(), Charsets.UTF_8) }
    } catch (e: Exception) {
        Log.e(TAG, "cannot read asset $name", e)
        ""
    }

    companion object {
        private const val TAG = "RfPort"
        private const val PREF = "ada"
        /* The old single-folder key: read once at startup and then filed under its game's key. */
        private const val KEY_GAME = "game_tree_uri"
        /* The game the entry screen last pointed at: what auto-start starts. */
        private const val KEY_LAST_GAME = "last_game"
        /* Whether the entry screen goes straight into the last game instead of showing the cards. */
        private const val KEY_AUTO_START = "auto_start_last"
        private const val MENU_SELECT_GAME = 1
        private const val MENU_SELECT_SAVES = 2
        private const val MENU_HELP = 3
        /* The extra a shortcut carries: the GameProfile.id of the game it opens. */
        private const val EXTRA_GAME = "game"
        private const val MENU_LINK = 4
        /* A card's art tile: a square, so the crop is square too (see decodeArt). */
        private const val TILE_DP = 72
        /* The round play badge on a card, and the tap target of its three-dot folder menu. */
        private const val PLAY_DP = 44
        private const val DOTS_DP = 32
        /* The global setting that turns WebView inspection on for a released build. */
        private const val DEBUG_WEBVIEW_SETTING = "rfport_webview_debug"
        /* The single saves folder versions before per-game folders used; read once, in the migration. */
        private const val KEY_SAVES = "saves_tree_uri"

        /* Whether the on-screen pad draws its controls. */
        private const val KEY_PAD = "on_screen_pad"
        /* Whether the two sticks start where the screen half is touched (the side menu's switch). */
        private const val KEY_DYNAMIC_STICKS = "dynamic_sticks"
        /* Whether external input (controller, mouse or keyboard) hides the on-screen overlay (the
         * side menu's switch). The stored key string is kept as-is so installs keep their setting. */
        private const val KEY_HIDE_EXTERNAL_INPUT = "hide_with_controller"
        /* Where the picture sits vertically: a ViewAlign.wire value. */
        private const val KEY_VIEW_ALIGN = "view_align"
        /* Whether the next start of the game should drop the game's stored Resolution option: set
         * from the side menu's Reset resolution row, or from the pre-game screen where there is no
         * page to reload yet, and cleared as the URL that carries it to the shim is built. */
        private const val KEY_RESET_VIDEO = "reset_video"
        /* Whether the shim draws its frame-rate/resolution/battery/thermal readout. */
        private const val KEY_STATS = "stats_overlay"
        /* Whether the shim caps the page's frame rate, for battery, and at what (see [FpsLimit]). */
        private const val KEY_LIMIT_FPS = "limit_fps"
        private const val KEY_FPS_LIMIT = "fps_limit"
        private const val REQ_GAME = 101
        private const val REQ_SAVES = 102
        private const val BRIDGE_NAME = "PortBridge"

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

        /**
         * ANGLE Preferences, under both of the names its apk ships with: AOSP's `com.android.angle`
         * and Google's `com.google.android.angle` (the activity is `MainActivity` in both).
         */
        private val ANGLE_PREFERENCES = listOf(
            ComponentName("com.android.angle", "com.android.angle.MainActivity"),
            ComponentName("com.google.android.angle", "com.google.android.angle.MainActivity"),
        )
    }
}
