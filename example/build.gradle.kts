plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    mingwX64 {
        binaries {
            sharedLib {
                baseName = "il2cpp4k-example"
                // 导出库内全部符号：既让框架入口 il2cpp4k_init 可见，也便于用 objdump 自检
                linkerOpts("-Wl,--export-all-symbols")
            }
        }
    }

    androidNativeArm64 {
        binaries {
            sharedLib {
                baseName = "il2cpp4k-example"
                linkerOpts("-Wl,--export-dynamic")
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":api"))
        }
    }
}
