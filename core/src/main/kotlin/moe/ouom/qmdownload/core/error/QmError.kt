package moe.ouom.qmdownload.core.error

sealed class QmError(message: String) : Exception(message) {
    class AuthExpired : QmError("QQ 音乐登录已过期，请重新扫码")
    class Network(cause: Throwable) : QmError("网络请求失败：${cause.message ?: "未知错误"}")
    class Api(val code: Int, message: String?) :
        QmError("QQ 音乐接口错误：$code ${message.orEmpty()}".trim())

    class NoPermission : QmError("当前账号无权访问该内容")
    class NoResource(message: String = "当前歌曲没有可用音质") : QmError(message)
    class InvalidInput(message: String) : QmError(message)
}
