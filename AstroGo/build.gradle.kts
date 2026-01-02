cloudstream {
    description = "Astro Go Extension"
    authors = listOf("Antigravity")
    language = "en"
    tvTypes = listOf("TvSeries", "Movie")
    isCrossPlatform = true
}

version = 1

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
}
