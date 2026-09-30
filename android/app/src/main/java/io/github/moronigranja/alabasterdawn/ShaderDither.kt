package io.github.moronigranja.alabasterdawn

/**
 * The engine dithers a light's fade — and a fading background's — with the ordered 4x4 threshold table
 * in `terra/data/shader/lib/dithering.glsl`, indexed by `gl_FragCoord.xy` divided by a scale. Five
 * shaders divide by `u_screenScale * u_ditherScale`: `u_ditherScale` is 1 and `u_screenScale` is the
 * resolution option's scale, so **one cell is one art pixel**. The dither is a *binary* discard (a
 * fragment below its threshold is dropped and the unlit layer behind shows), so at a fade of 0.5 half
 * the fragments go: the dots are not a 1-LSB modulation, and a 4-cell repeat is four art pixels. On a
 * phone panel — where an art pixel is 2-3 device pixels — that reads as a grid texture over the
 * terrain (issue #3, FINDINGS §18).
 *
 * The engine's own `solid.frag` dithers at the **render** pixel instead (`/ u_ditherScale`, and raw
 * `gl_FragCoord.xy` for its radial dither), as does `lib/water.glsl`'s radial dither, where the pattern
 * is a per-pixel texture and invisible. Those five shaders are the ones on the art grid, and the port
 * serves them on the finer one.
 *
 * Nothing else changes: the same thresholds, the same `discard`, the same varyings — only the divisor
 * of the coordinate the pattern is indexed by. `u_screenScale` stays declared and set by the engine.
 */
object ShaderDither {

    /** The engine's art-grid divisor, verbatim from the five shaders. */
    private const val ART_GRID = "vec2 screenCoord = gl_FragCoord.xy / (u_screenScale * u_ditherScale);"

    /** The engine's render-grid divisor, verbatim from `solid.frag`. */
    private const val RENDER_GRID = "vec2 screenCoord = gl_FragCoord.xy / u_ditherScale;"

    /** The five shaders whose dither is on the art grid; every other dither site is already finer. */
    private val SHADERS = setOf(
        "solid-simple-light.frag",
        "solid-back.frag",
        "solid-overlap.frag",
        "solid-simple.frag",
        "shadow-map.frag",
    )

    /** Whether [rel] is one of the shaders this rewrites, so the response is worth caching. */
    fun rewrites(rel: String): Boolean = rel.substringAfterLast('/') in SHADERS

    /** The served text of a shader: its dither indexed by the render pixel, or its own bytes back. */
    fun rewrite(rel: String, text: String): String =
        if (rewrites(rel)) text.replace(ART_GRID, RENDER_GRID) else text
}
