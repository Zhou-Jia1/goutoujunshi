plugins { alias(libs.plugins.kotlin.jvm); alias(libs.plugins.kotlin.serialization) }
kotlin { jvmToolchain(17) }
dependencies {
    api(libs.coroutines)
    api(libs.serialization)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
