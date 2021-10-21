package shark

import okio.BufferedSource
import okio.Okio
import shark.explanation.Doc
import java.io.File

/**
 * Represents the header metadata of a Hprof file.
 */
data class HprofHeader(
        /** Unix timestamp at which the heap was dumped. */
        val heapDumpTimestamp: Long = System.currentTimeMillis(),
        /** Hprof version, which is tied to the runtime where the heap was dumped. */
        val version: HprofVersion = HprofVersion.ANDROID,
        /**
         * Size of Hprof identifiers. Identifiers are used to represent UTF8 strings, objects,
         * stack traces, etc. They can have the same size as host pointers or sizeof(void*), but are not
         * required to be.
         */
        val identifierByteSize: Int = 4
) {
    /**
     * How many bytes from the beginning of the file can we find the hprof records at.
     * Version string, 0 delimiter (1 byte), identifier byte size int (4 bytes) ,timestamp long
     * (8 bytes)
     */
    val recordsPosition: Int = version.versionString.toByteArray(Charsets.UTF_8).size + 1 + 4 + 8

    companion object {
        private val supportedVersions = HprofVersion.values()
                .map { it.versionString to it }
                .toMap()

        /**
         * Reads the header of the provided [hprofFile] and returns it as a [HprofHeader]
         */
        fun parseHeaderOf(hprofFile: File): HprofHeader {
            val fileLength = hprofFile.length()
            if (fileLength == 0L) {
                throw IllegalArgumentException("Hprof file is 0 byte length")
            }
            return Okio.buffer(Okio.source(hprofFile.inputStream())).use {
                parseHeaderOf(it)
            }
        }

        /**
         * Reads the header of the provided [source] and returns it as a [HprofHeader].
         * This does not close the [source].
         */
        fun parseHeaderOf(source: BufferedSource): HprofHeader {
            require(!source.exhausted()) {
                throw IllegalArgumentException("Source has no available bytes")
            }

            @Doc("1.获取第一个0出现的下标，根据规范，hprof文件最开始存放的就是该hprof的版本，紧接着就是0")
            val endOfVersionString = source.indexOf(0)

            @Doc("2.读取从最开始到第一次出现0的这一段，解码为UTF-8，并从bufferedSource中移除它")
            val versionName = source.readUtf8(endOfVersionString)

            val version = supportedVersions[versionName]
            checkNotNull(version) {
                "Unsupported Hprof version [$versionName] not in supported list ${supportedVersions.keys}"
            }
            // Skip the 0 at the end of the version string.
            source.skip(1)
            val identifierByteSize = source.readInt()
            val heapDumpTimestamp = source.readLong()
            val heapDumpTimestampEnd = source.readLong()

//            println(endOfVersionString)
//            println(versionName)
//            println(version)
//            println(identifierByteSize)
//            println(heapDumpTimestamp)
//            println(heapDumpTimestampEnd)

            return HprofHeader(heapDumpTimestamp, version, identifierByteSize)
        }
    }
}