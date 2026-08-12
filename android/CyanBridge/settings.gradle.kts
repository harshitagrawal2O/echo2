pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://jitpack.io") }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        // JetBrains Compose Multiplatform (Skiko native binaries for iOS)
        maven { url = uri("https://maven.packagist.org") }

        // Meta Wearables DAT SDK (requires GitHub token with read:packages scope)
        val localProps = java.util.Properties()
        val localPropsFile = rootDir.resolve("local.properties")
        if (localPropsFile.exists()) {
            localProps.load(localPropsFile.inputStream())
        }
        // Declared only for the meta variant. Keying this on token presence alone was the last
        // place the old token-selects-the-variant assumption survived: CI sets GITHUB_TOKEN for
        // unrelated reasons, so every CI run was declaring a Meta repository it could never
        // authenticate against. Harmless while nothing resolves from it, but it is the same
        // wrong coupling the -PmetaSupport flag exists to remove.
        // startParameter.projectProperties, not project.hasProperty: this is a Settings script and
        // has no project receiver. A project-style lookup here would not fail loudly — it would
        // evaluate false, silently skip the repository, and surface much later as "could not find
        // com.meta.wearable:mwdat-core", which reads like a Meta access problem rather than a
        // settings-script bug. Logged because nobody without the grant can reach the code path
        // that would otherwise reveal it.
        val metaSupportRequested =
            startParameter.projectProperties["metaSupport"]?.toBoolean() == true
        if (metaSupportRequested) {
            logger.lifecycle("settings: metaSupport=true, declaring the Meta Packages repository")
        }
        val githubToken = System.getenv("GITHUB_TOKEN")
            ?: localProps.getProperty("github_token")
        if (metaSupportRequested && !githubToken.isNullOrBlank()) {
            maven {
                url = uri("https://maven.pkg.github.com/facebook/meta-wearables-dat-android")
                credentials {
                    username = ""
                    password = githubToken
                }
            }
        }
    }
}
rootProject.name = "CyanBridgeManagerApp"
include(":app")
include(":shared")

// Moonshine Voice (local wrapper module that builds vendored native sources)
include(":moonshine-voice")

// HeyCyan Core - bundled as composite build for easy compilation
val heycyanCoreDir = file("../../heycyan-core")
if (heycyanCoreDir.exists()) {
    includeBuild(heycyanCoreDir)
}
