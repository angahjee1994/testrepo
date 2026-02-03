android {
    namespace = "com.hot51"
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
}

cloudstream {
    description = "Hot51"
    language = "id"
    authors = listOf("botol")
    status = 3
    tvTypes = listOf("Live")
    iconUrl = "https://raw.githubusercontent.com/angahjee1994/testrepo/refs/heads/main/HotLive11/logo.png"
}
