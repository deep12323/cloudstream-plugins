import java.io.File

dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
}

version = 1

cloudstream {
    description = "Transforms CloudStream's entire UI (Home, Details, Search, Navigation & Video Player) into a sleek Netflix-style experience"
    authors = listOf("deepu2135")
    status = 1
    tvTypes = listOf("Others")
    requiresResources = false
    language = "en"
    iconUrl = "https://raw.githubusercontent.com/google/material-design-icons/master/png/av/play_circle_filled/materialicons/48dp/2x/baseline_play_circle_filled_white_48dp.png"
}

android {
    namespace = "com.netflixplayerui"
    defaultConfig {
        consumerProguardFiles("proguard-rules.pro")
    }
}

afterEvaluate {
    tasks.findByName("make")?.apply {
        doLast {
            val cs3File = file("build/NetflixPlayerUI.cs3")
            if (cs3File.exists()) {
                val rootBuildsFile = rootProject.file("builds/${cs3File.name}")
                rootBuildsFile.parentFile.mkdirs()
                cs3File.copyTo(rootBuildsFile, overwrite = true)
                println("Updated cs3 file successfully copied to root builds: ${rootBuildsFile.absolutePath}")
            }
        }
    }
}
