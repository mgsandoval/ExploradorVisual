plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.exploradorvisualparanios"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.exploradorvisualparanios"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    // Agrega estas líneas para CameraX
    implementation ("androidx.camera:camera-core:1.3.1")
    implementation ("androidx.camera:camera-camera2:1.3.1")
    implementation ("androidx.camera:camera-lifecycle:1.3.1")
    implementation ("androidx.camera:camera-view:1.3.1")

    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    // Dependencia de ML Kit Image Labeling (on-device)
    implementation("com.google.mlkit:image-labeling:17.0.8")
    // ML Kit para traducción automática
    implementation("com.google.mlkit:translate:17.0.2")

    // CardView para un diseño más sencillo con bordes redondeados y sombras
    implementation("androidx.cardview:cardview:1.0.0")
    implementation(libs.activity)
    implementation(libs.constraintlayout)

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}