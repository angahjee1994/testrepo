// use an integer for version numbers
version = 1


cloudstream {
    language = "ms"
    // All of these properties are optional, you can safely remove them

    description = "Astro Go Provider"
    authors = listOf("botol")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 3 // will be 3 if unspecified
    tvTypes = listOf("LiveTV", "Movies", "TvSeries")

    // iconUrl = ""
}
