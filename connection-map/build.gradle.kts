plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = "com.connectionmap"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // Gson for JSON serialization (required for GlobalMap)
    implementation("com.google.code.gson:gson:2.10.1")
    
    intellijPlatform {
        intellijIdeaCommunity("2024.1")
        instrumentationTools()
        
        // JCEF for browser-based UI
        bundledPlugin("com.intellij.java")
    }
}

kotlin {
    jvmToolchain(17)
}

intellijPlatform {
    pluginConfiguration {
        id = "com.connectionmap"
        name = "Connection Map"
        version = "1.0.0"
        description = "Visualize file dependencies as an interactive graph"
        vendor {
            name = "Connection Map Team"
        }
    }
    
    buildSearchableOptions = false
    
    // Disable plugin verification to avoid issues
    pluginVerification {
        ides {
            // Empty - skip verification
        }
    }
}

tasks {
    runIde {
        jvmArgs("-Xmx2g")
    }
}
