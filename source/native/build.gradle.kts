plugins {
    alias(libs.plugins.library.common)
}

android {
    namespace = "com.xayah.libnative"
    ndkVersion = "25.2.9519653"

    defaultConfig {
        externalNativeBuild {
            cmake {
                arguments.addAll(listOf("-DANDROID_PLATFORM=28"))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    externalNativeBuild {
        cmake {
            path("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    // AndroidX
    implementation(libs.androidx.core.ktx)
}