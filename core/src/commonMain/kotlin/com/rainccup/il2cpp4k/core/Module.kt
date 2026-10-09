package com.rainccup.il2cpp4k.core

expect object Module {
    /** [name] 为 null 时返回主模块基址；找不到返回 0。 */
    fun base(name: String?): Long

    /** 在指定模块中查找导出符号；[module] 为 null 表示主模块。找不到返回 null。 */
    fun symbol(module: String?, name: String): Long?
}
