import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

version = 3

cloudstream {
    description = "My M3U playlist with day/time wise catchup"
    authors = listOf("me")

    // 1 = working
    status = 1

    tvTypes = listOf("Live", "TvSeries")

    requiresResources = false
    language = "en"
}

extensions.configure<com.android.build.gradle.BaseExtension>("android") {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}
