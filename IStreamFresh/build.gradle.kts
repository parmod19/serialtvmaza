version = 2

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

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")
}

cloudstream {
    description = "IStreamFresh - Movies/Series/Anime"
    language = "hi"
    authors = listOf("parmod19")
    status = 1
    tvTypes = listOf("AsianDrama", "TvSeries", "Anime", "Movie", "Cartoon", "AnimeMovie")
}
