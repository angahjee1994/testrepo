version = 1

cloudstream {
    description = "Kissasian"
    language = "en"
    authors = listOf("Duro92")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "AsianDrama",
    )

    iconUrl = "https://raw.githubusercontent.com/angahjee1994/testrepo/refs/heads/main/Kissasian/logo.png"
}