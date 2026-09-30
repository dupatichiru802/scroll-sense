import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasKeystoreProperties = keystorePropertiesFile.exists()
if (hasKeystoreProperties) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

android {
    namespace = "com.dupati.scrollsense"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dupati.scrollsense"
        minSdk = 24
        targetSdk = 36
        versionCode = 3
        versionName = "1.1"
        // Gates the long-press Pro unlock in MainActivity. MUST be false before
        // building the upload for Play Store — otherwise anyone can long-press
        // their way to free Pro. Flip to false, then build, for the real release.
        buildConfigField("boolean", "DEV_UNLOCKS_ENABLED", "false")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (hasKeystoreProperties) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasKeystoreProperties) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.android.billingclient:billing-ktx:8.0.0")

    // Cloud settings sync: Google Sign-In + Firebase Auth/Firestore.
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
}
