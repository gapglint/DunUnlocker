import com.android.build.api.variant.impl.VariantOutputImpl
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.lsparanoid)
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) {
        file.inputStream().use(::load)
    }
}

fun signingProp(envKey: String, localKey: String): String? =
    System.getenv(envKey)?.takeIf { it.isNotBlank() }
        ?: localProps.getProperty(localKey)?.takeIf { it.isNotBlank() }

android {
    namespace = "dev.naijun.dununlocker"
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.compileSdkMinor.get().toInt()
        }
    }

    defaultConfig {
        applicationId = "com.gapglint.dununlocker"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = libs.versions.versionCode.get().toInt()
        versionName = libs.versions.versionName.get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val keystorePath = signingProp("SIGNING_KEYSTORE_PATH", "signing.keystorePath")
    val storePasswordValue = signingProp("SIGNING_STORE_PASSWORD", "signing.storePassword")
    val keyAliasValue = signingProp("SIGNING_KEY_ALIAS", "signing.keyAlias")
    val keyPasswordValue = signingProp("SIGNING_KEY_PASSWORD", "signing.keyPassword")
    val hasReleaseSigning = keystorePath != null &&
        storePasswordValue != null &&
        keyAliasValue != null &&
        keyPasswordValue != null &&
        file(keystorePath).isFile

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = storePasswordValue
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    buildFeatures {
        compose = true
    }

    //noinspection WrongGradleMethod
    androidComponents {
        onVariants { variant ->
            variant.outputs
                .map { it as VariantOutputImpl }
                .forEach { output ->
                    val versionName = libs.versions.versionName.get()
                    val buildType = variant.buildType

                    if (buildType == "release") {
                        output.outputFileName = "dununlocker-gapglint-v${versionName}-${buildType}.apk"
                    }
                }
        }
    }

    androidResources {
        generateLocaleConfig = true
    }
}

lsparanoid {
    variantFilter = { variant ->
        if (variant.buildType == "release") {
            seed = 0x4E41494A
            includeDependencies = false
            classFilter = { className ->
                className.startsWith("dev.naijun.dununlocker.") &&
                    className != "dev.naijun.dununlocker.BuildConfig" &&
                    className != "dev.naijun.dununlocker.R" &&
                    !className.startsWith("dev.naijun.dununlocker.R$")
            }
            true
        } else {
            false
        }
    }
}

dependencies {
    compileOnly(project(":hidden-api-stub"))
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.hiddenapibypass)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
