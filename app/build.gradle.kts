plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mcpanel"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mcpanel.server"
        minSdk = 24
        targetSdk = 28
        versionCode = 1
        versionName = "1.0.0"
        resourceConfigurations += listOf("zh", "en")
    }

    signingConfigs {
        // Release signing is optional: create app/release.jks (or provide the
        // MCPANEL_* environment variables) to build a signed release APK.
        if (file("release.jks").exists()) {
            val storePass = System.getenv("MCPANEL_STORE_PASSWORD")
                ?: throw GradleException("请设置 MCPANEL_STORE_PASSWORD 环境变量（或删除 app/release.jks 使用 debug 签名）")
            val keyPass = System.getenv("MCPANEL_KEY_PASSWORD") ?: storePass
            create("release") {
                storeFile = file("release.jks")
                storePassword = storePass
                keyAlias = System.getenv("MCPANEL_KEY_ALIAS") ?: "mcpanel"
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        viewBinding = true
    }
    androidResources {
        noCompress += listOf("xz", "gz", "deb")
    }
    lint {
        // targetSdk 28 is required on purpose: Android 10+ blocks executing binaries from
        // the app data directory for targetSdk >= 29, which the portable Java runtime needs.
        abortOnError = false
        checkReleaseBuilds = false
        disable += "ExpiredTargetSdkVersion"
    }
    packaging {
        resources {
            excludes += setOf("META-INF/INDEX.LIST", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.tukaani:xz:1.9")
}
