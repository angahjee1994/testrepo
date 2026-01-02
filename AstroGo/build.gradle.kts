import com.lagradost.cloudstream3.gradle.CloudstreamExtension 

cloudstream {
    // set your extensions information here
    name = "Astro Go"
    label = "Astro Go"
    description = "Astro Go Extension with hardcoded credentials"
    authors = listOf("Antigravity")
    recommends = listOf("com.lagradost.cloudstream3")
    
    // minimal version code
    versionCode = 1
    // version name
    versionName = "1.0.0"
}
