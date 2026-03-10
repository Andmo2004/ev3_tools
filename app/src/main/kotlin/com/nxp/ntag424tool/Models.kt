package com.nxp.ntag424tool

data class OperationResult<T>(
    val success: Boolean,
    val message: String,
    val data: T? = null
) {
    companion object {
        fun ok(message: String) = OperationResult<Unit>(true, message)
        fun <T> ok(message: String, data: T) = OperationResult(true, message, data)
        fun fail(message: String) = OperationResult<Unit>(false, message)
    }
}

data class CardInfo(
    val uid: String = "--",
    val cardType: String = "--",
    val vendor: String = "--",
    val hwMajor: String = "--",
    val hwMinor: String = "--",
    val swMajor: String = "--",
    val swMinor: String = "--",
    val storage: String = "--",
    val batchNo: String = "--",
    val freeMemory: String = "--"
)

data class AppInfo(
    val aid: ByteArray = ByteArray(3),
    val label: String = ""
) {
    val aidHex: String get() = aid.toHexString()
}

data class FileInfo(
    val fileNo: Int = 0,
    val type: FileType = FileType.STANDARD,
    val commMode: CommMode = CommMode.PLAIN,
    val readKey: Int = 0x0E,
    val writeKey: Int = 0x0E,
    val rwKey: Int = 0x0E,
    val changeKey: Int = 0x0E,
    val size: Int = 0
)

enum class FileType { STANDARD, BACKUP, VALUE, LINEAR_RECORD, CYCLIC_RECORD }
enum class CommMode { PLAIN, MAC, FULL }

data class NewAppConfig(
    val aid: ByteArray = byteArrayOf(0x01, 0x00, 0x00),
    val numKeys: Int = 5
)

data class NewFileConfig(
    val fileNo: Int = 0,
    val size: Int = 256,
    val commMode: CommMode = CommMode.PLAIN,
    val readKey: Int = 0x0E,
    val writeKey: Int = 0x0E,
    val rwKey: Int = 0x0E,
    val changeKey: Int = 0x00
)
