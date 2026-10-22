import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Optional release signing. Point the Gradle property pblockSigningProps (e.g. in
// ~/.gradle/gradle.properties) or the environment variable PBLOCK_SIGNING_PROPS at a
// properties file with storeFile, storePassword, keyAlias and keyPassword.
// Without it, assembleRelease produces an unsigned APK. Never commit keys or passwords.
val releaseSigningProps: Properties? =
    (providers.gradleProperty("pblockSigningProps").orNull
        ?: providers.environmentVariable("PBLOCK_SIGNING_PROPS").orNull)
        ?.let { file(it) }
        ?.takeIf { it.isFile }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

android {
    namespace = "com.saworks.pblock"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.saworks.pblock"
        minSdk = 29
        targetSdk = 36
        versionCode = 6
        versionName = "0.5.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        releaseSigningProps?.let { p ->
            create("release") {
                storeFile = file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    lint {
        // Every user-facing string must exist in all shipped languages.
        error += "MissingTranslation"
        error += "MissingQuantity"
        checkReleaseBuilds = false
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true // BuildConfig.VERSION_NAME / DEBUG for the version label
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.core.splashscreen)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
