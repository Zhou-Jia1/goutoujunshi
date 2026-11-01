plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.serialization); alias(libs.plugins.ksp) }
android {
    namespace = "com.shengjidaguai.goutoujunshi.data"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    buildToolsVersion = "37.0.0"
    defaultConfig { minSdk = 26; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets["androidTest"].assets.srcDir("schemas")
    buildFeatures { aidl = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    api(project(":core:domain"))
    implementation(libs.core)
    implementation(libs.coroutines.android)
    implementation(libs.onnxruntime.android)
    implementation(libs.opencv.android)
    implementation(libs.serialization)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore)
    implementation(libs.sqlcipher)
    implementation(libs.sqlite)
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.hilt)
    ksp(libs.hilt.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.room.testing)
    testImplementation(libs.test.core)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.junit)
    androidTestImplementation(libs.test.core)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.room.testing)
}
