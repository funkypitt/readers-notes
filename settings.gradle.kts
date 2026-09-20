pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ReadersNotes"
include(":app")

// Speech on the phone (whisper.cpp and its models), shared with the sibling apps as a git submodule.
include(":speech")
