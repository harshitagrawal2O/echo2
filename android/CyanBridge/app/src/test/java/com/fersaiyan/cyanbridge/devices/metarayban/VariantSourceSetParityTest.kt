package com.fersaiyan.cyanbridge.devices.metarayban

import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Guards the one structural risk the `src/meta` / `src/nometa` split creates: the variant nobody
 * can compile silently falling behind the one everybody compiles.
 *
 * Exactly one of the two source sets is compiled, selected by `-PmetaSupport` in `app/build.gradle`.
 * The `nometa` twin is compiled by every build on every machine. The `meta` twin needs the DAT
 * artifact, which needs a `read:packages` grant on `facebook/meta-wearables-dat-android`. So a
 * member that exists on the `nometa` side and is missing on the `meta` side compiles everywhere,
 * passes every build anyone here can run, and is wrong only on the variant no one can check.
 *
 * That is not hypothetical. `MetaCameraPermission` overrode `ActivityResultContract`'s third member
 * `getSynchronousResult` on the `nometa` side and not on the `meta` side, for the whole life of the
 * split. It was found by a person reading the file, which is not a mechanism.
 *
 * ### What this test checks, and what it does not
 *
 * It compares the set of `(kind, name)` declarations in each paired file and fails on any that the
 * `nometa` twin has and the `meta` twin lacks.
 *
 * The check is deliberately **directional**. The real `MetaRaybanManager` legitimately has far more
 * members than its no-op twin — the current counts are 169 against 54 — so `meta`-only declarations
 * are expected and ignored. Only the reverse direction indicates the twins have drifted.
 *
 * `private` declarations are excluded, because the thing that has to match is the surface `src/main`
 * compiles against, and a private member is not part of it. Two implementations of the same contract
 * are entitled to different internals: the `nometa` twin holds a `private const val UNAVAILABLE`
 * error string that the real one has no use for, which is a difference in how the two are written
 * rather than the meta side missing something. That was this check's first finding when it was run,
 * and it was a false positive — excluding private declarations is the fix, and is more honest than
 * whitelisting one name.
 *
 * It compares **names, not signatures**. Signature comparison is the obvious extension and was
 * tried; it does not survive contact with this codebase. Declarations here routinely wrap across
 * several lines — `getSynchronousResult` itself does — so a line-based signature comparison would be
 * vacuously comparing `override fun getSynchronousResult(` on both sides while appearing to check
 * something. Doing it properly needs a parser. The consequence is stated plainly: **a member present
 * on both sides with divergent parameter or return types passes this test.** Catching that still
 * requires compiling `src/meta`.
 *
 * A pleasant side effect of comparing names is that it removes the type-normalisation problem
 * outright: `val uri: android.net.Uri?` against `val uri: Uri?` is the same declaration by name, so
 * no whitelist and no simple-name rewriting is needed for the one known-benign difference.
 *
 * ### If this test fails
 *
 * Either a real divergence was introduced, or a *local* `val`/`var` inside a function body exists
 * only in the `nometa` twin. This is a line scanner, not a parser, and cannot tell a local binding
 * from a member. The second case is a false positive; add it to [KNOWN_NOMETA_ONLY] with a comment
 * saying why. That set is empty today, and every entry added to it is a hole in this check.
 */
class VariantSourceSetParityTest {

    private companion object {

        /**
         * Declarations allowed to exist only in the `nometa` twin. Empty, and worth keeping empty —
         * see the class doc. Format: `"<file>:<kind> <name>"`, e.g. `"MetaRaybanManager.kt:val x"`.
         */
        val KNOWN_NOMETA_ONLY = emptySet<String>()

        /**
         * Group 1 is the modifier run, which is why it is captured rather than skipped: `override`
         * is a modifier, and an anchored pattern that required the keyword first would not see
         * `override fun getSynchronousResult` at all — the exact declaration this test exists for.
         */
        val DECLARATION = Regex(
            """^\s*((?:[a-z]+\s+)*)(fun|val|var|class|object|interface)\s+([A-Za-z_][A-Za-z0-9_]*)""",
        )
    }

    @Test
    fun `every member of a nometa twin exists on the meta side`() {
        val moduleRoot = locateModuleRoot()
        val metaRoot = File(moduleRoot, "src/meta/java")
        val nometaRoot = File(moduleRoot, "src/nometa/java")

        check(metaRoot.isDirectory) { "missing source set: $metaRoot" }
        check(nometaRoot.isDirectory) { "missing source set: $nometaRoot" }

        val pairs = kotlinSourcesByRelativePath(nometaRoot)
        check(pairs.isNotEmpty()) { "found no Kotlin sources under $nometaRoot" }

        val problems = mutableListOf<String>()

        for ((relativePath, nometaFile) in pairs) {
            val metaFile = File(metaRoot, relativePath)
            if (!metaFile.isFile) {
                problems += "$relativePath exists in src/nometa but not in src/meta"
                continue
            }

            val metaDeclarations = declarationsIn(metaFile)
            val nometaOnly = declarationsIn(nometaFile) - metaDeclarations
            val fileName = nometaFile.name

            for (declaration in nometaOnly.sorted()) {
                if ("$fileName:$declaration" in KNOWN_NOMETA_ONLY) continue
                problems += "$relativePath declares `$declaration` in src/nometa but not in src/meta"
            }
        }

        if (problems.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("The meta twin has fallen behind the nometa twin.")
                    appendLine()
                    problems.forEach { appendLine("  - $it") }
                    appendLine()
                    append(
                        "Every build here compiles src/nometa and none compiles src/meta, so this " +
                            "difference is invisible to the compiler. Add the member to the meta " +
                            "twin, or if this is a local binding rather than a member, whitelist " +
                            "it in KNOWN_NOMETA_ONLY with a reason.",
                    )
                },
            )
        }
    }

    /**
     * Non-private declarations as `"<kind> <name>"`, scanned line by line at every nesting depth —
     * members of anonymous objects included, which is where the bug that motivated this test lived.
     * Comment lines are skipped so that a KDoc mentioning `fun foo` does not register as one.
     */
    private fun declarationsIn(file: File): Set<String> =
        file.readLines()
            .asSequence()
            .map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .mapNotNull { DECLARATION.find(it) }
            .filterNot { "private" in it.groupValues[1].split(" ") }
            .map { "${it.groupValues[2]} ${it.groupValues[3]}" }
            .toSet()

    private fun kotlinSourcesByRelativePath(root: File): Map<String, File> =
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associateBy { it.relativeTo(root).invariantSeparatorsPath }

    /**
     * The unit-test working directory is not contractual across Gradle versions and IDE runners, so
     * the module is found by walking up rather than assumed.
     */
    private fun locateModuleRoot(): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            if (File(directory, "src/meta/java").isDirectory) return directory
            val appModule = File(directory, "app")
            if (File(appModule, "src/meta/java").isDirectory) return appModule
            directory = directory.parentFile
        }
        error("could not locate the app module from ${System.getProperty("user.dir")}")
    }
}
