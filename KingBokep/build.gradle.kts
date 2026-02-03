// use an integer for version numbers
version = 1


cloudstream {
    language = "id"
    // All of these properties are optional, you can safely remove them

    description = "KingBokep extension"
    authors = listOf("botol")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 3 // will be 3 if unspecified
    tvTypes = listOf("NSFW")

    // iconUrl = "https://raw.githubusercontent.com/angahjee1994/testrepo/refs/heads/main/KingBokep/logo.png" 
}
