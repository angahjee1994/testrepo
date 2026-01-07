// use an integer for version numbers
version = 24


cloudstream {
    language = "id"
    // All of these properties are optional, you can safely remove them

    // description = "Lorem Ipsum"
     authors = listOf("Duro92", "CodeSanzz")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "AnimeMovie",
        "OVA",
        "Anime",
    )

    iconUrl = "https://raw.githubusercontent.com/angahjee1994/testrepo/refs/heads/main/Samehadaku/logo.png"
}