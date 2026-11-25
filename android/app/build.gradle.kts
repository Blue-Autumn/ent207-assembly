plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.arassemblydemo"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.example.arassemblydemo"
        minSdk = 26
        targetSdk = 36
        versionCode = 67
        versionName = "0.5.47"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

androidComponents.onVariants(androidComponents.selector().withBuildType("release")) {
    it.packaging.jniLibs.useLegacyPackaging.set(true)
}

dependencies {
    implementation("com.huawei.hms:arenginesdk:4.0.0.5")
    implementation("androidx.activity:activity:1.10.1")
    implementation("androidx.camera:camera-core:1.6.2")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("org.opencv:opencv:4.13.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

// Check labels and spoken instructions on the normal IDE/Gradle packaging path.
val checkAssemblyLanguages by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    val python = if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
    commandLine(python, "-X", "utf8", "tools/check_release_languages.py")
}
tasks.named("preBuild") { dependsOn(checkAssemblyLanguages) }

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
