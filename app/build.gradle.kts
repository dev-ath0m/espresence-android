plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.espresense.node"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.espresense.node"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    // Release signing comes from the environment only: never keep a keystore or a
    // password in the repo. Android treats the signing key as the identity of the
    // app, so anyone holding it can build a package the device accepts as an
    // update -- which is exactly what a self-update mechanism relies on.
    //
    // Locally: export ESPRESENSE_KEYSTORE=/keys/release.jks and the passwords.
    // In CI: store the keystore base64-encoded as a *secret* and decode it at
    // build time. Losing this key means no in-place updates ever again; the app
    // must be uninstalled (losing its settings) before a differently-signed
    // build will install. Keep a backup outside the repo.
    val keystorePath = System.getenv("ESPRESENSE_KEYSTORE")
    val keystoreFile = keystorePath?.let { file(it) }?.takeIf { it.exists() }

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = keystoreFile
                storePassword = System.getenv("ESPRESENSE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ESPRESENSE_KEY_ALIAS") ?: "espresense-node"
                keyPassword = System.getenv("ESPRESENSE_KEY_PASSWORD")
                    ?: System.getenv("ESPRESENSE_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Fall back to debug signing so a plain `assembleRelease` still works
            // for anyone building without the key.
            signingConfig = if (keystoreFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-service:2.8.3")
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
