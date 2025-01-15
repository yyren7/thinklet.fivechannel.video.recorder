import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.module
import org.gradle.kotlin.dsl.version

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}

allprojects {
    configurations.all {
        resolutionStrategy.dependencySubstitution {
            // `androidx.camera:camera-video` をTHINKLET向けのライブラリに差し替える
            val video = thinkletLibs.camerax.video.get()
            substitute(module("androidx.camera:camera-video"))
                .using(module("${video.module.group}:${video.module.name}:${video.version}"))
                .because("To support THINKLET specific mics")
        }
    }
}
