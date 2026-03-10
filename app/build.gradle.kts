import java.util.Properties
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    compileSdk = 33
    namespace = "com.nxp.ntag424tool"

    defaultConfig {
        applicationId = "com.nxp.ntag424tool"
        minSdk = 21
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"
        multiDexEnabled = true
        buildConfigField("String", "TAPLINX_KEY", "\"${localProps.getProperty("taplinx_key", "")}\"")
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs += listOf("-opt-in=kotlin.ExperimentalStdlibApi")
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packagingOptions {
        resources.excludes += setOf(
            "META-INF/LICENSE.txt",
            "META-INF/NOTICE.txt"
        )
    }

    lint {
        abortOnError = false
    }
}

repositories {
    flatDir { dirs("libs") }
    google()
    mavenCentral()
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.9.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.multidex:multidex:2.0.1")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation(files("libs/NxpNfcAndroidLib.aar"))
    implementation("com.madgag.spongycastle:pkix:1.54.0.0")
    implementation("com.madgag.spongycastle:prov:1.54.0.0")
    implementation("com.madgag.spongycastle:core:1.54.0.0")
}
