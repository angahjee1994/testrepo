version = 1

cloudstream {
    language = "en"
    authors = listOf("Phisher98")

    status = 1
    tvTypes = listOf(
        "TvSeries",
        "Movie",
    )

    iconUrl = "https://www.astro.com.my/favicon.ico"
}

android {
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.legacy:legacy-support-v4:1.0.0")
    implementation("com.google.android.material:material:1.4.0")
    implementation("androidx.recyclerview:recyclerview:1.2.1")
}
