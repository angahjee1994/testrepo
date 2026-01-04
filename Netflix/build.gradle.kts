// use an integer for version numbers
version = 1

android {
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    
    language = "en"
    // All of these properties are optional, you can safely remove them

    description = "Netflix Contents in Multiple Languages"
    authors = listOf("botol")

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

    iconUrl = "https://raw.githubusercontent.com/angahjee1994/testrepo/refs/heads/main/Netflix/logo.png"
}
