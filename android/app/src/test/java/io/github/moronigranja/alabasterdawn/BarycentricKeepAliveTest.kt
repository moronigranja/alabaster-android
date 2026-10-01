package io.github.moronigranja.alabasterdawn

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The keep-alive `GameAssetHandler` inserts into fragment shaders whose vertex stage declares
 * `v_barycentric`: one unreachable, data-dependent use of the varying, so the driver keeps the attribute
 * chain alive. Adreno drops `a_barycentricIdx` otherwise and the title screen throws
 * "Attribute does not exist!".
 *
 * The branch must never run — and "never" only holds **in the shader's own precision**. The sentinel is
 * `-1e30`, which `mediump` cannot represent (range ±65504); where the varying is garbage — the water
 * fragments, whose program is the one that loses the attribute — the comparison fired and `main()`
 * returned before drawing anything, so the water was invisible on every device (found 2026-09-30 on the
 * S22 Ultra). Making the sentinel representable is *not* the repair: with `-1e4` and the game's own
 * mediump water shaders the driver's compiler stalls the boot (`99.9%, pending 2 (shader=2)`). The repair
 * is serving the water **stages** at highp, where this sentinel means what it says.
 *
 * So this test asserts the pair, not the constant: while the sentinel is outside `mediump`, the four water
 * files must be served raised even on a device that needs no lift. That is the guard that was missing.
 */
class BarycentricKeepAliveTest {

    /** `mediump` in ES 3.0 guarantees at least ±2^14. */
    private val mediumpMax = 16384.0

    private val waterFiles = listOf(
        "terra/data/shader/fragment/water-plane.frag",
        "terra/data/shader/fragment/water-fx-wall.frag",
        "terra/data/shader/vertex/water-plane.vert",
        "terra/data/shader/vertex/water-fx-wall.vert",
    )

    @Test
    fun `a sentinel mediump cannot hold implies the water stages are served at highp`() {
        val line = GameAssetHandler.keepAliveLine()
        assertTrue("the keep-alive must use the varying", line.contains("v_barycentric"))
        assertTrue("and must not discard (early-Z on tile-based GPUs)", !line.contains("discard"))

        val sentinel = Regex("""vec3\((-?[\d.eE+-]+)\)""").find(line)?.groupValues?.get(1)?.toDouble()
        assertTrue("no sentinel found in: $line", sentinel != null)
        if (abs(sentinel!!) <= mediumpMax) return   // representable everywhere: nothing else to require

        ShaderArrays.reset()
        ShaderArrays.report(false)                  // a device whose compiler accepts the game's declarations
        for (rel in waterFiles) {
            assertTrue("$rel must be served anyway: the keep-alive's sentinel needs highp", ShaderArrays.rewrites(rel))
            assertTrue(
                "$rel must raise the default precision",
                ShaderArrays.edits(rel).any { it.first == "precision mediump float;" && it.second == "precision highp float;" },
            )
        }
        ShaderArrays.reset()
    }
}
