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

rootProject.name = "remoter"

include(
    ":app",
    ":core:design",
    ":core:crypto",
    ":core:net",
    ":core:testing",
    ":feature:onboarding",
    ":feature:home",
    ":feature:browser",
    ":feature:session",
    ":feature:settings",
    ":benchmark",
    ":e2e",
)
