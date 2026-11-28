import java.io.File

version = 2

cloudstream {
    description = "Stream unabridged audiobooks from AudioBookBay via TorrServer & BitTorrent"
    authors = listOf("deepu2135")
    status = 1
    tvTypes = listOf("AudioBook", "TvSeries", "Torrent")
    requiresResources = false
    language = "en"
    iconUrl = "https://audiobookbay.lu/apple-touch-icon.png"
}

android {
    namespace = "com.audiobookbay"
    defaultConfig {
        consumerProguardFiles("proguard-rules.pro")
    }
}

afterEvaluate {
    tasks.findByName("make")?.apply {
        doLast {
            val cs3File = file("build/AudioBookBay.cs3")
            if (cs3File.exists()) {
                val rootBuildsFile = rootProject.file("builds/${cs3File.name}")
                rootBuildsFile.parentFile.mkdirs()
                cs3File.copyTo(rootBuildsFile, overwrite = true)
                println("Updated cs3 file successfully copied to root builds: ${rootBuildsFile.absolutePath}")
            }
        }
    }
}
