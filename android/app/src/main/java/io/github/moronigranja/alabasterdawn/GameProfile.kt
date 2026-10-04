package io.github.moronigranja.alabasterdawn

/**
 * One game this host can run.
 *
 * Both games are NW.js apps from Radical Fish Games, but they are *different engines*: Alabaster
 * Dawn is the `terra` engine (`terra/index.html` → `terra/dist/bundle.js`, custom GLSL under
 * `terra/data/shader/`), CrossCode is Cubic Impact 0.5 (`assets/node-webkit.html` →
 * `assets/js/game.compiled.js`, a jQuery/Impact canvas game). Everything the host does per game —
 * where the entry page lives, which shim the page gets, where the game's own version is written, and
 * whether the engine-specific rewrites apply at all — is this list.
 *
 * The picked folder is the game's install root: it holds [pageRoot] plus the game's own `package.json`.
 * A profile is selected by looking for its entry page in the index ([detect]), so nothing is stored
 * and switching games is switching folders.
 *
 * What is deliberately *not* here: the shim's platform stubs. Each engine needs a different Node/NW.js
 * surface (Alabaster needs `window.process` undefined, CrossCode needs it to be an object), and each
 * shim is a self-contained script rather than a shared core — the two engines are the only two
 * Radical Fish NW.js games, and keeping the proven Alabaster shim untouched is worth the duplication.
 * If a third game ever appears, that is when the shared part earns an extraction.
 */
enum class GameProfile(
    /** Stable id, used for the diagnostics log name. */
    val id: String,
    /** How the game is named on the pre-game screen and in the record. */
    val displayName: String,
    /**
     * The engine's page root, relative to the picked folder. Every URL under `/game/` and every
     * page-relative `fs` path hangs off this (the engines' own roots are empty strings, so a resource
     * asks for `data/...` and it resolves under here).
     */
    val pageRoot: String,
    /** The engine's entry page, relative to [pageRoot]. */
    val entryHtml: String,
    /** The shim asset under `android/app/src/main/assets/` this game's page gets. */
    val shimAsset: String,
    /**
     * Substring of the entry page the fallback `<script>` tag is placed next to, for WebViews without
     * document-start scripting. [shimAfterAnchor] says which side of it.
     */
    val shimAnchor: String,
    val shimAfterAnchor: Boolean,
    /** The game's own version source, relative to the picked root; null when it has none. */
    val versionPath: String?,
    /**
     * Query parameter the shim watches to drop the game's stored resolution option before the engine
     * reads it (see the Alabaster shim's `resetStoredResolution`); null when the game has no such
     * option to jam.
     */
    val resetVideoParam: String?,
    /**
     * Whether the host applies the engine-specific *asset* rewrites (resolution ladder, uniform-slot
     * table, array-declaration lifts, the barycentric keep-alive, the dither grid). All of them are
     * Alabaster Dawn's `terra` shaders and bundle; CrossCode's canvas engine has neither the files
     * nor the problems.
     */
    val rewrites: Boolean,
    /** Whether the side menu's Top/Centre/Bottom picture control means anything to this engine. */
    val viewAlign: Boolean,
    /** The render canvas the port's overlays measure. Both engines own the element's own layout. */
    val canvasSelector: String,
    /**
     * The game's own art inside its install, relative to the picked root, for its card's tile on the
     * entry screen: each game's *title art* — Alabaster Dawn's emblem, CrossCode's character scene.
     * It is **drawn from the user's copy at runtime and never shipped** — the APK carries no game art
     * (see `NOTICE.md`), and a build that moved the file simply gets no image.
     *
     * Both are the games' *composed* art, not their atlases: `game-logo-small.png` and CrossCode's
     * `title-logo.png` are sprite sheets, whose opaque filler draws as black slabs on a panel.
     */
    val artPath: String,
) {
    ALABASTER_DAWN(
        id = "ada",
        displayName = "Alabaster Dawn",
        pageRoot = "terra",
        entryHtml = "index.html",
        shimAsset = "ada-shim.js",
        shimAnchor = "<script src=\"dist/bundle.js\"",
        shimAfterAnchor = false,
        versionPath = "terra/data/database/changelog.json",
        resetVideoParam = "adaResetVideo",
        rewrites = true,
        viewAlign = true,
        canvasSelector = ".xgCanvas",
        artPath = "terra/media/gui/title/title-bg-01.png",
    ),
    CROSSCODE(
        id = "cc",
        displayName = "CrossCode",
        pageRoot = "assets",
        entryHtml = "node-webkit.html",
        shimAsset = "cc-shim.js",
        /* The entry's scripts start in <head>; the shim must land before the first of them. */
        shimAnchor = "<head>",
        shimAfterAnchor = true,
        versionPath = "assets/data/changelog.json",
        /* Its saves are localStorage, and it has no resolution option the port jams. */
        resetVideoParam = null,
        /* Cubic Impact, canvas 2D: none of the GLSL rewrites have a file to land on. */
        rewrites = false,
        viewAlign = false,
        canvasSelector = "#canvas",
        artPath = "assets/media/gui/title-bg.png",
    );

    /** The entry page's index key (also its path under the picked root). */
    val indexHtml: String get() = "$pageRoot/$entryHtml"

    /** The URL the WebView loads; the asset loader serves the picked folder under `/game/`. */
    val entryUrl: String get() = "$ORIGIN/game/$indexHtml"

    /**
     * Where the fallback script tag finds the shim: inside the page root, so the page loads it
     * relatively. Neither `ada-shim.js` nor `cc-shim.js` exists in either game.
     */
    val shimPath: String get() = "$pageRoot/$shimAsset"

    /**
     * Where the app remembers this game's install folder. One key per game, so both folders can be
     * kept at once and either game can be started from the entry screen without re-picking.
     */
    val gameFolderKey: String get() = "game_tree_uri_$id"

    /** Where the app remembers this game's saves folder. One key per game, so each keeps its own. */
    val savesFolderKey: String get() = "saves_tree_uri_$id"

    companion object {
        /** The asset-loader origin the picked folder is served from. */
        const val ORIGIN = "https://appassets.androidplatform.net"

        /**
         * The profile whose entry page the picked folder carries, or null when it carries neither —
         * the user picked a folder that is not one of these games' install roots.
         */
        fun detect(index: GameIndex): GameProfile? =
            entries.firstOrNull { index.find(it.indexHtml) != null }
    }
}
