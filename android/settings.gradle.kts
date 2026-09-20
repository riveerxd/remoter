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
    ":core:design",
    ":core:crypto",
    ":core:net",
    ":core:testing",
    ":feature:onboarding",
    ":feature:home",
    ":feature:session",
    ":e2e",
)
