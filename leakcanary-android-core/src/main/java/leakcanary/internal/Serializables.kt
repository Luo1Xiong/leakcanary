package leakcanary.internal

import shark.SharkLog
import java.io.*

internal fun Serializable.toByteArray(): ByteArray {
    val outputStream = ByteArrayOutputStream()
    ObjectOutputStream(outputStream).writeObject(this)
    return outputStream.toByteArray()
}

internal object Serializables {

    inline fun <reified T> fromByteArray(byteArray: ByteArray): T? {
        val inputStream = ByteArrayInputStream(byteArray)
        return try {
            ObjectInputStream(inputStream).readObject() as? T
        } catch (ignored: Throwable) {
            SharkLog.d(ignored) { "Could not deserialize bytes, ignoring" }
            null
        }
    }
}