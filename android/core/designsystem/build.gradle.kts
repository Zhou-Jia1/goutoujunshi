plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.compose); alias(libs.plugins.roborazzi) }
android {
    namespace = "com.shengjidaguai.goutoujunshi.designsystem"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    buildToolsVersion = "37.0.0"
    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.material)
    api(libs.compose.preview)
    debugImplementation(libs.compose.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.compose.test)
    debugImplementation(libs.compose.test.manifest)
}
