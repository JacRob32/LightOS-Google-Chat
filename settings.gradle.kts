pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    includeBuild("light-sdk-reference/plugin")
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "light-google-chat"

include(":tool", ":lint-rules", ":sdk:shared", ":sdk:ui", ":sdk:client")
include(":host-tests")
project(":sdk").projectDir = file("light-sdk-reference/sdk")
project(":lint-rules").projectDir = file("light-sdk-reference/lint-rules")
listOf("shared", "ui", "client").forEach { module ->
    project(":sdk:$module").projectDir = file("light-sdk-reference/sdk/$module")
}
