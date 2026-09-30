package io.github.moronigranja.alabasterdawn

/**
 * The GUI shaders declare `precision mediump float;`; every world shader declares `highp`.
 *
 * That difference is invisible on the desktop build: desktop GL has no real mediump, so it is
 * promoted to highp and the same bytes render the same. A mobile GPU honours it (fp16 storage and
 * ALU), and the GUI's maths — screen-space `floor()`/`mod()` for the shaded fills, `fract()` tiling
 * for the tiled ones — is exactly the kind that quantises visibly. Reported on an Adreno device as a
 * regular stripe pattern on the *shaded* GUI fills only (the selected side-menu row, the slider
 * track), at the same base colour the desktop build renders flat; the panel, the text and the icons
 * — which use no such maths — were clean. See FINDINGS §16.
 *
 * ES 3.0 guarantees `highp` in both stages, so the served bytes can simply be raised. Where mediump
 * already means highp this changes the declaration and nothing else.
 */
object ShaderPrecision {

    private const val MEDIUMP = "precision mediump float;"
    private const val HIGHP = "precision highp float;"

    /** The shaders the port raises. Only the GUI's: the reported artefact is theirs, and the world
     *  shaders are already highp. */
    private val GUI = setOf("gui.frag", "gui.vert", "gui-bg.frag", "gui-blur.frag")

    /** Whether [rel] is a shader this raises, so the response is worth caching. */
    fun rewrites(rel: String): Boolean = rel.substringAfterLast('/') in GUI

    /** The served bytes of a GUI shader: its own precision declaration, raised. */
    fun rewrite(rel: String, body: ByteArray): ByteArray {
        if (!rewrites(rel)) return body
        val text = String(body, Charsets.UTF_8)
        val raised = text.replaceFirst(MEDIUMP, HIGHP)
        return if (raised == text) body else raised.toByteArray(Charsets.UTF_8)
    }
}
