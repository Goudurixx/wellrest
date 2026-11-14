import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: create keystore.properties at the project root (never commit it):
//   storeFile=/absolute/path/upload-keystore.jks
//   storePassword=...
//   keyAlias=upload
//   keyPassword=...
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.sylvainrousseau.wellrest"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sylvainrousseau.wellrest"
        minSdk = 26
        targetSdk = 36 // Google Play: new apps & updates must target API 36 since 31 Aug 2026
        // CI overrides these from the git tag: ./gradlew bundleRelease -PversionName=1.5.0 -PversionCode=42
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 10601
        versionName = (findProperty("versionName") as String?) ?: "1.6.1"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = when {
                keystoreProps.isNotEmpty() -> signingConfigs.getByName("release")
                // CI preview releases before the upload key is configured: installable, not for Play
                findProperty("previewSigning") == "true" -> signingConfigs.getByName("debug")
                else -> null
            }
        }
        debug {
            versionNameSuffix = "-debug"
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
}
