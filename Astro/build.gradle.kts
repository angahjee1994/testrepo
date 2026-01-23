
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
    description = "Astro Live TV"
    authors = listOf("botol")
    status = 1
    tvTypes = listOf(
        "Live",
    )
    iconUrl = "https://dj7fdt04hl8tv.cloudfront.net/acm/media/contenthub/shop/icon_chinese-favourites.png"
}
