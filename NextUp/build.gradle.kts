import java.io.File

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
}

version = 1

cloudstream {
    description = "AI-powered personalized movie, TV & anime recommendations using Simkl and Gemini with Open-With & CineSimkl support"
    authors = listOf("deepu2135")
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime")
    requiresResources = false
    language = "en"
    iconUrl = "https://raw.githubusercontent.com/google/material-design-icons/master/png/action/recommend/materialicons/48dp/2x/baseline_recommend_white_48dp.png"
}

android {
    namespace = "com.nextup.ai"
    defaultConfig {
        consumerProguardFiles("proguard-rules.pro")
    }
}

afterEvaluate {
    tasks.findByName("make")?.apply {
        doLast {
            val cs3File = file("build/NextUp.cs3")
            if (cs3File.exists()) {
                val rootBuildsFile = rootProject.file("builds/${cs3File.name}")
                rootBuildsFile.parentFile.mkdirs()
                cs3File.copyTo(rootBuildsFile, overwrite = true)
                println("Updated cs3 file successfully copied to root builds: ${rootBuildsFile.absolutePath}")
            }
        }
    }
}
