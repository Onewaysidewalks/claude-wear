pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "claude-wear"

// Phase 0: proves button invocation on the real watch. Throwaway once `watch` holds the role.
include(":spike")
// Types shared by the phone and the watch: Data Layer paths, message codecs, gateway events.
include(":shared")
// The Android companion: the only thing on the tailnet, holds the gateway session.
include(":phone")
// The Wear OS app: listen, think, speak. Talks to the phone, never to the gateway.
include(":watch")
