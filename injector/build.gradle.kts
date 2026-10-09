plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    mingwX64 {
        binaries {
            executable {
                baseName = "il2cpp4k-inject"
                entryPoint = "com.rainccup.il2cpp4k.injector.main"
            }
            executable("il2cpp4k-inject-target") {
                baseName = "il2cpp4k-inject-target"
                entryPoint = "com.rainccup.il2cpp4k.injector.targetMain"
            }
        }
    }

    androidNativeArm64 {
        binaries {
            executable {
                baseName = "il2cpp4k-inject"
                entryPoint = "com.rainccup.il2cpp4k.injector.main"
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":logging"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
