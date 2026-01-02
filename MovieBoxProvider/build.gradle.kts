// use an integer for version numbers
version = 5

android {
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    language = "en"
    // All of these properties are optional, you can safely remove them

    description = "Multi Language Movies and Series Provider"
    authors = listOf("NivinCNC,Phisher98")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )

    iconUrl = "https://raw.githubusercontent.com/angahjee1994/testrepo/refs/heads/main/MovieBoxProvider/logo.png"

    requiresResources = true
    isCrossPlatform = false
}
