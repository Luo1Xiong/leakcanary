package shark

import java.nio.charset.StandardCharsets
import java.util.*
import kotlin.collections.ArrayList


fun printByteArrayToHexArray(byteArray: ByteArray) {
    var result = ""
    for (idx in byteArray.indices) {
        val hexStr = Integer.toHexString(byteArray[idx].toInt())
        result += (if (hexStr.length == 1) "0$hexStr" else if (hexStr.length > 2) hexStr.substring(hexStr.length - 2, hexStr.length) else hexStr) + " "
    }
    println(result + " -> " + String(byteArray, StandardCharsets.UTF_8))
}

fun printByteArrayToStr(byteArray: ByteArray) {
    val intArray = ArrayList<Int>()
    for (idx in byteArray.indices) {
        val hexStr = byteArray[idx].toInt()
        intArray.add(hexStr)
    }
    println(Arrays.toString(intArray.toArray()))
}

fun printByteArrayToBinaryStr(byteArray: ByteArray) {
    var binaryStr = ""
    val hexArray = arrayOfNulls<String>(byteArray.size)
    for (idx in byteArray.indices) {
        val intStr = byteArray[idx].toInt()
        hexArray[idx] = Integer.toHexString(intStr)
        binaryStr += Integer.toBinaryString(intStr)
    }
    println(hexArray.contentToString() + " -> " + binaryStr)
}