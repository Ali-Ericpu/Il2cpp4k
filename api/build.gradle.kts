plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    mingwX64 {
        binaries {
            sharedLib {
                baseName = "il2cpp4k"
                // 导出库内全部全局符号，使 @CName("il2cpp4k_init") 出现在 DLL 导出表中
                linkerOpts("-Wl,--export-all-symbols")
            }
        }
    }

    androidNativeArm64 {
        binaries {
            sharedLib {
                baseName = "il2cpp4k"
                linkerOpts("-Wl,--export-dynamic")
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            // 日志 API 是框架对外接口的一部分：用 api 让消费方直接可用 Log.*
            api(project(":logging"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
