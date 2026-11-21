import java.io.File

dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
}

version = 2

cloudstream {
    description = "Stream movies & TV series from DesireMovies (HubCloud, GDFlix direct R2, Google CDN & Pixeldrain Bypass)"
    authors = listOf("deepu2135")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
    language = "en"
    iconUrl = "https://1desiremovies.xyz/wp-content/uploads/2023/04/cropped-image_processing20210911-26622-fplmvm-192x192.png"
}

android {
    namespace = "com.desiremovies"
    defaultConfig {
        consumerProguardFiles("proguard-rules.pro")
    }
}

afterEvaluate {
    tasks.findByName("make")?.apply {
        doLast {
            val cs3File = file("build/DesireMovies.cs3")
            if (cs3File.exists()) {
                val rootBuildsFile = rootProject.file("builds/${cs3File.name}")
                rootBuildsFile.parentFile.mkdirs()
                cs3File.copyTo(rootBuildsFile, overwrite = true)
                println("Updated cs3 file successfully copied to root builds: ${rootBuildsFile.absolutePath}")
            }
        }
    }
}
