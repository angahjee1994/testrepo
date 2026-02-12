version = 1

android {
    namespace = "com.teraboxvirals"
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}

cloudstream {
    description = "TeraboxVirals"
    language = "ms"
    authors = listOf("botol")
    status = 3
    tvTypes = listOf("NSFW")
    iconUrl = "https://raw.githubusercontent.com/angahjee1994/testrepo/refs/heads/main/TeraboxVirals/logo.png"
}
