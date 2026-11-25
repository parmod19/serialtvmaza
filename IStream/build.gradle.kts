version = 1

android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

cloudstream {
    description = "iStream provider"
    authors = listOf("parmod19")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    language = "hi"
}
