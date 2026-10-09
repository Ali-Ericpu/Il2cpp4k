package com.rainccup.il2cpp4k.injector

/** 退出码与错误分类（码值与规格 S3 一致）。 */
enum class InjectError(val code: Int, val label: String) {
    USAGE(1, "usage error"),
    TARGET_NOT_FOUND(2, "target process not found or ambiguous"),
    OPEN_FAILED(3, "failed to open target process"),
    LIB_NOT_FOUND(4, "library file not found or unreadable"),
    INJECT_FAILED(5, "injection failed"),
    SYMBOL_NOT_FOUND(6, "symbol not found"),
    TIMEOUT(7, "remote call timed out"),
    VERIFY_FAILED(8, "post-injection verification failed"),
}

class InjectFailure(val error: InjectError, message: String) : RuntimeException(message)

class UsageException(message: String) : RuntimeException(message)
