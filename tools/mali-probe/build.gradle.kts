/* The Mali shader probe: a second, standalone Gradle project (never part of the port's build), which
 * compiles the game's own shader text on a device's **native** GL front end and reports what that
 * front end says. See README.md next to this file, and FINDINGS.md §22. */
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
}
