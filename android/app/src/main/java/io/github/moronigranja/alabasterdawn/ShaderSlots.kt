package io.github.moronigranja.alabasterdawn

/**
 * The game's vertex shaders declare `uniform vec2 u_texSlotCoords[TEX_SLOT_COUNT]` (256 elements) and
 * the engine sizes its own upload and its texture atlases from the same constant (`const
 * TEX_SLOT_COUNT = 256;` in `terra/dist/bundle.js`).
 *
 * On the drivers seen here one `vec2` array element costs a whole uniform *vector*: on the A14
 * emulator (`MAX_VERTEX_UNIFORM_VECTORS = 256`, SwiftShader) every one of the 12 vertex shaders fails
 * with `ERROR: too many uniforms`, the staged shader resources never finalize, `checkTrackers()`
 * throws and the boot bar freezes at 7.9-12 % - which is issue #1. 256 is also the value GLES3
 * guarantees, so a device that reports exactly that is not broken, it is at the floor.
 *
 * Two rules, both measured against the engine's own live shader objects on that emulator:
 *
 * * the table is `budget - RESERVE`, because the shaders that carry one table need 48 more vectors for
 *   matrices, wind uniforms and the imported `lib` glsl files (`weather-drops.vert` is the heaviest).
 *   192 vectors of table plus that reserve fit 256, and every one-table program links at 192.
 * * `gui.vert` is the shader that sets the ceiling: it declares **two** tables, the gui atlas and the
 *   font atlas (`uniform vec2 u_fontSlotCoords[...]`), so at 192 it would cost 384 vectors on its own
 *   and its own uniforms ~30 more. Where the device cannot afford both unpacked - which is any device
 *   below `2 * 256 + 32` - the port packs each table into `vec4`s: one vector per *two* slots, with
 *   the slot count unchanged. The engine uploads the same `2 * TEX_SLOT_COUNT` floats with a call
 *   chosen from the program's own uniform type (`getActiveUniform` -> `FLOAT_VEC4` -> `uniform4fv`),
 *   and `uniform4fv` into `vec4[TEX_SLOT_COUNT / 2]` validates exactly (`2 * S / 4 == S / 2`), which is
 *   how the port can serve 192 slots where the naive fix would have served 96. Verified on the
 *   emulator: `gui.vert` links at 192 packed and the game's own text renders correctly from the packed
 *   font table.
 *
 * A device with room for the game's own tables is served **unmodified bytes**: nothing here runs unless
 * the device is at the GLES3 floor. Both constants move together - the served `.vert` bytes *and* the
 * served `bundle.js` - or the engine would upload 512 floats into a shorter array. The files on the
 * user's disk are never touched.
 */
object ShaderSlots {

    /** The game's own table size: what `bundle.js` and every vertex shader say. */
    const val GAME = 256

    /** Vectors a one-table shader needs besides the table (measured: 48), with margin. */
    const val RESERVE = 64

    /** Vectors `gui.vert` needs besides its two packed tables (measured: ~30), with margin. */
    const val PACK_RESERVE = 32

    /** The lowest table worth serving if a device reports a small budget. */
    const val FLOOR = 64

    const val DEFINE = "#define TEX_SLOT_COUNT $GAME"
    const val BUNDLE_CONSTANT = "const TEX_SLOT_COUNT = $GAME;"

    /** A slot table's declaration, as every shader writes it. */
    private val TABLE = Regex("""uniform\s+vec2\s+(u_\w*SlotCoords)\s*\[TEX_SLOT_COUNT\];""")

    /** What the page's GL stack reported through `AdaBridge.setVertexUniformVectors`; 0 until then. */
    @Volatile
    var vertexUniformVectors: Int = 0

    /** The budget to plan against: what the page said, or the GLES3 minimum when it never answered. */
    private fun budget(vertexUniformVectors: Int): Int =
        if (vertexUniformVectors <= 0) GAME else vertexUniformVectors

    /**
     * The table size to serve: the game's own 256 when the device has room for it, otherwise what is
     * left of the budget after the shaders' own uniforms. A device whose page never answered is
     * treated as the minimum, so the failure mode is a smaller atlas and never a shader the device
     * cannot compile.
     */
    fun slotsFor(vertexUniformVectors: Int): Int =
        (budget(vertexUniformVectors) - RESERVE).coerceIn(FLOOR, GAME)

    /** The table size the app serves right now. */
    fun slots(): Int = slotsFor(vertexUniformVectors)

    /**
     * Whether the multi-table shaders must be packed: true whenever the budget cannot hold both of
     * `gui.vert`'s tables at the game's own size.
     */
    fun packsTables(vertexUniformVectors: Int): Boolean =
        2 * GAME + PACK_RESERVE > budget(vertexUniformVectors)

    /** Whether anything at all has to be rewritten for this device. */
    fun rewrites(vertexUniformVectors: Int): Boolean =
        slotsFor(vertexUniformVectors) < GAME || packsTables(vertexUniformVectors)

    /** How many slot tables a shader declares; only `gui.vert` declares two. */
    fun tables(text: String): Int = TABLE.findAll(text).count()

    /**
     * The served bytes of a shader: 256 replaced by [slots] in its `#define`, and - only for a shader
     * that declares more than one table, when [pack] - those tables packed into `vec4`s.
     */
    fun rewriteShader(text: String, slots: Int, pack: Boolean): String {
        val counted = if (slots >= GAME) text else text.replace(DEFINE, "#define TEX_SLOT_COUNT $slots")
        return if (pack && tables(counted) > 1) packTables(counted) else counted
    }

    /**
     * Replaces 256 with [slots] in the engine's own constant (and in any text that carries it), which
     * must agree with the shaders or the upload would be longer than the array it lands in.
     */
    fun rewrite(text: String, slots: Int): String = when {
        slots >= GAME -> text
        else -> text
            .replace(DEFINE, "#define TEX_SLOT_COUNT $slots")
            .replace(BUNDLE_CONSTANT, "const TEX_SLOT_COUNT = $slots;")
    }

    /**
     * `uniform vec2 u_texSlotCoords[TEX_SLOT_COUNT];` becomes
     *
     * ```
     * uniform vec4 u_texSlotCoords[TEX_SLOT_COUNT / 2];
     * vec2 ada_u_texSlotCoords(uint i) { vec4 v = u_texSlotCoords[i >> 1u]; return ((i & 1u) == 0u) ? v.xy : v.zw; }
     * vec2 ada_u_texSlotCoords(int i) { return ada_u_texSlotCoords(uint(i)); }
     * ```
     *
     * and `u_texSlotCoords[expr]` becomes `ada_u_texSlotCoords(expr)`. Slot `i` lives in element `i >> 1`
     * of the packed table, in its xy half for even slots and zw for odd ones - which is exactly the
     * layout the engine's `2 * TEX_SLOT_COUNT` floats already have. Both overloads are generated because
     * the shaders index with `int(...)` and with `uint(...)`; the two-component result keeps every call
     * site's type unchanged, and no dynamic component index is needed.
     */
    fun packTables(text: String): String {
        val names = TABLE.findAll(text).map { it.groupValues[1] }.toList()
        if (names.isEmpty()) return text
        var packed = text
        for (name in names) {
            val use = Regex(Regex.escape(name) + """\[([^\]]+)\]""")
            packed = use.replace(packed) { m ->
                if (m.groupValues[1].trim() == "TEX_SLOT_COUNT") m.value
                else "ada_$name(${m.groupValues[1]})"
            }
        }
        return TABLE.replace(packed) { m ->
            val name = m.groupValues[1]
            "uniform vec4 $name[TEX_SLOT_COUNT / 2];\n" +
                "vec2 ada_$name(uint i) { vec4 v = $name[i >> 1u]; return ((i & 1u) == 0u) ? v.xy : v.zw; }\n" +
                "vec2 ada_$name(int i) { return ada_$name(uint(i)); }"
        }
    }
}
