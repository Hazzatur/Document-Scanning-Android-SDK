plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

android {
    namespace = "com.zynksoftware.documentscanner"
    compileSdk = 35

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        named("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            java.srcDir("src/main/kotlin")
            res.srcDirs("src/main/res")
            manifest.srcFile("src/main/AndroidManifest.xml")
        }
    }

    buildFeatures {
        viewBinding = true
    }
}

tasks.register<Jar>("sourceJar") {
    from(android.sourceSets["main"].java.srcDirs)
    from(fileTree(mapOf("dir" to "src/libs", "include" to listOf("*.jar"))))
    archiveClassifier.set("sources")
}

tasks.register<Jar>("androidSourcesJar") {
    archiveClassifier.set("sources")
    from(android.sourceSets["main"].java.srcDirs)
}

afterEvaluate {
    publishing {
        publications {
            create("release", MavenPublication::class) {
                from(components["release"])
                groupId = "com.github.hazzatur"
                artifactId = "Document-Scanning-Android-SDK"
                version = "1.3.0"
            }
        }
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.rxandroid)
    implementation(libs.tiny.opencv)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.kpermissions)
    implementation(libs.exifinterface)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.compressor)
    implementation(libs.androidx.test.monitor)
    testImplementation(libs.junit.jupiter)
    androidTestImplementation(libs.junit.jupiter)
}
