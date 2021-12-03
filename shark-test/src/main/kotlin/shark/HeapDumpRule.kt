package shark

import org.junit.rules.ExternalResource
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.*

class HeapDumpRule : ExternalResource() {
    private val temporaryFolder = TemporaryFolder()

    @Throws(Throwable::class)
    override fun before() {
        temporaryFolder.create()
    }

    override fun after() {
        temporaryFolder.delete()
    }

    @Throws(IOException::class)
    fun dumpHeap(): File {
        val hprof = File(temporaryFolder.root, "heapDump" + UUID.randomUUID() + ".hprof")
        JvmTestHeapDumper.dumpHeap(hprof.absolutePath)
        return hprof
    }
}

fun main() {

    testBreak()
}

fun testBreak() {
    val list = arrayListOf<String>().apply {
        add("a")
        add("b")
        add("c")
    }

    list.run {
        forEach { value ->
            println("run : $value start")
            if (value == "b") return@run
            println("run : $value finished")
        }
    }

    println("run finished")
}
