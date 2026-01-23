version = 1

android {
    namespace = "com.botol"
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
}

cloudstream {
    language = "en"
    description = "Streaming Drama Pendek"
    authors = listOf("botol")
    status = 1
    tvTypes = listOf(
        "AsianDrama",
    )
    iconUrl = "https://raw.githubusercontent.com/angahjee1994/testrepo/refs/heads/main/Dramabox/logo.png"
}
