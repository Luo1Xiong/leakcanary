package com.example.leakcanary

//import leakcanary.internal.activity.screen.HeapDumpRenderer
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.collection.ArrayMap
import androidx.core.content.ContextCompat
import shark.*
import shark.HeapAnalyzer.Companion.deduplicateShortestPaths
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.internal.AndroidNativeSizeMapper
import shark.internal.PathFinder
import shark.internal.ShallowSizeCalculator
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.*
import kotlin.collections.ArrayList

class SecondActivity : Activity() {
    enum class HprofTask {
        Analyze,
        CreateBitmap,
        FindAllBitmapInfo
    }

    var mImageViewHprof: ImageView? = null
    var mBitmap: Bitmap? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.second_activity)
        mImageViewHprof = findViewById(R.id.iv_hprof)
        findViewById<Button>(R.id.analyze).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                checkPermissionAndRun(HprofTask.Analyze)
            else
                analyzeHprof()
        }

        findViewById<Button>(R.id.create_bitmap).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                checkPermissionAndRun(HprofTask.CreateBitmap)
            else
                createBitmapFromHprof()
        }

        findViewById<Button>(R.id.find_all_bitmaps).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                checkPermissionAndRun(HprofTask.FindAllBitmapInfo)
            else
                findBigInstanceInfo()
        }
    }

    private val permissionRequestCode = 10000

    @RequiresApi(Build.VERSION_CODES.M)
    private fun checkPermissionAndRun(hprofTask: HprofTask) {
        if (ContextCompat.checkSelfPermission(this@SecondActivity, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this@SecondActivity, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            when (hprofTask) {
                HprofTask.Analyze -> analyzeHprof()
                HprofTask.CreateBitmap -> createBitmapFromHprof()
                HprofTask.FindAllBitmapInfo -> findBigInstanceInfo()
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), permissionRequestCode);
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == permissionRequestCode) {
        }
    }

    private fun createBitmapFromHprof() {
        val hprofFile = File("/sdcard/testHprof.hprof")
        if (!hprofFile.exists()) {
            return
        }

//        val bitmapHprof = HeapDumpRenderer.render(this@SecondActivity, hprofFile, 4000, 0, 10)
//
//        val pngFile = File("/sdcard/testHprof.png")
//        val saveResult = savePng(pngFile, bitmapHprof)
//        println("saveResult:$saveResult")
    }

    fun savePng(
            imageFile: File,
            source: Bitmap
    ): Boolean {
        var outStream: FileOutputStream? = null
        return try {
            outStream = FileOutputStream(imageFile)
            source.compress(Bitmap.CompressFormat.PNG, 100, outStream)
            true
        } catch (e: IOException) {
            println("saveResult:$e")
            false
        } finally {
            outStream?.close()
        }
    }

    private fun analyzeHprof() {
        val hprofFile = File("/sdcard/testHprof.hprof")
        if (!hprofFile.exists()) {
            return
        }
        val heapAnalyzer = HeapAnalyzer(OnAnalysisProgressListener { step -> Log.i("sharkAnalyzer", "onAnalysisProgress:$step : ${Thread.currentThread()}") })

        val sharkResult = heapAnalyzer.analyze(
                heapDumpFile = hprofFile,
                leakingObjectFinder = FilteringLeakingObjectFinder(AndroidObjectInspectors.appLeakingObjectFilters),
                referenceMatchers = AndroidReferenceMatchers.appDefaults,
                computeRetainedHeapSize = true,
                objectInspectors = AndroidObjectInspectors.appDefaults,
                metadataExtractor = AndroidMetadataExtractor,
                proguardMapping = null
        )

//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
//            readHprof()
        print(sharkResult)
    }

    @RequiresApi(Build.VERSION_CODES.KITKAT)
    private fun readHprof() {
        val hprofFile = File("/sdcard/testHprof.hprof")
        val sourceProvider = ConstantMemoryMetricsDualSourceProvider(FileSourceProvider(hprofFile))
        val source = sourceProvider.openStreamingSource()

        val endOfVersionString = source.indexOf(0)
        println("firstIndexOf0:$endOfVersionString")
        printByteArrayToHexArray(source.readByteArray(endOfVersionString))
        printByteArrayToHexArray(source.readByteArray(1))
        printByteArrayToHexArray(source.readByteArray(4))
        printByteArrayToHexArray(source.readByteArray(8))
        for (i in 0 until 20) {
            printByteArrayToHexArray(source.readByteArray(4))
        }
    }

    @RequiresApi(Build.VERSION_CODES.KITKAT)
    private fun printByteArrayToHexArray(byteArray: ByteArray) {
        var result = ""
        for (idx in byteArray.indices) {
            val hexStr = Integer.toHexString(byteArray[idx].toInt())
            result += (if (hexStr.length == 1) "0$hexStr" else if (hexStr.length > 2) hexStr.substring(hexStr.length - 2, hexStr.length) else hexStr) + " "
        }
        Log.i("", result + " -> " + String(byteArray, StandardCharsets.UTF_8))
    }

    val instanceIdToSizes = ArrayMap<Long, InstanceIdWithSizes>()

    class InstanceIdWithSizes(val objectId: Long,
                              val nativeSize: Int = 0,
                              val shallowSize: Int = 0,
                              val retainedSize: Int = 0)

    class RetainedSizeComparator : Comparator<InstanceIdWithSizes> {
        override fun compare(o1: InstanceIdWithSizes, o2: InstanceIdWithSizes): Int {
            return o2.retainedSize - o1.retainedSize
        }
    }

    class InstanceInfoComparator : Comparator<InstanceInfo> {
        override fun compare(o1: InstanceInfo, o2: InstanceInfo): Int {
            return o2.retainedSize - o1.retainedSize
        }
    }

    private fun findBigInstanceInfo() {
        val hprofFile = File("/sdcard/testHprof.hprof")
        val proguardMappingFile = File("/sdcard/mapping.txt")
        val proguardMappingInputStream = FileInputStream(proguardMappingFile)
        val proguardMappingReader = ProguardMappingReader(proguardMappingInputStream)
        val heapGraph = hprofFile.openHeapGraph(proguardMappingReader.readProguardMapping())

        /**
         * 该方法耗时长，需要缓存起来
         */
        mapIdToNativeSizes = AndroidNativeSizeMapper(heapGraph).mapNativeSizes()
//        topTopCountInstancesReferenceQueue(heapGraph, mapIdToNativeSizes, "android.graphics.Bitmap", 5)
//        topTopCountInstancesReferenceQueue(heapGraph, mapIdToNativeSizes, "android.app.Activity", 5)
        topTopCountInstancesReferenceQueue(heapGraph, mapIdToNativeSizes, "android.app.Fragment", 5)
//        topTopCountInstancesReferenceQueue(heapGraph, mapIdToNativeSizes,"androidx.fragment.app.Fragment", 5)
    }

    companion object {
        const val Tag = "hprofStatistics"
        lateinit var mapIdToNativeSizes: Map<Long, Int>
        private val cachedTabs = ArrayMap<Int, String>()

        private fun buildTabsForIdx(idx: Int): String {
            var result = ""
            for (i in 0 until idx)
                result += " "
            return "$result↳"
        }

        fun getTabsForIdx(idx: Int): String {
            var cachedStr = cachedTabs[idx]
            if (cachedStr !is String) {
                cachedStr = buildTabsForIdx(idx)
            }
            return cachedStr
        }

        fun nameOfHeapObject(heapObject: HeapObject): String {
            return when (heapObject) {
                is HeapObject.HeapClass -> heapObject.asClass.toString()
                is HeapObject.HeapInstance -> heapObject.asInstance.toString()
                is HeapObject.HeapObjectArray -> heapObject.asObjectArray.toString()
                is HeapObject.HeapPrimitiveArray -> heapObject.asPrimitiveArray.toString()
                else -> "未识别的类型"
            }
        }

        /**
         * 计算shallowSize
         */
        private var shallowSizeCalculator: ShallowSizeCalculator? = null
        fun calculateShallowSizeOfObjectId(heapGraph: HeapGraph, objectId: Long): Int {
            if (shallowSizeCalculator == null) shallowSizeCalculator = ShallowSizeCalculator(heapGraph)
            return (shallowSizeCalculator as ShallowSizeCalculator).computeShallowSize(objectId)
        }

        fun logger(str: String) {
            println("$Tag:$str")
        }
    }

    private val mapClassNameToHeapClass = ArrayMap<String, HeapObject.HeapClass>()

    private fun getHeapClassForName(heapGraph: HeapGraph, heapClassName: String): HeapObject.HeapClass {
        var cachedHeapClass = mapClassNameToHeapClass[heapClassName]
        if (cachedHeapClass !is HeapObject.HeapClass) {
            // less than 10ms
            cachedHeapClass = heapGraph.findClassByName(heapClassName)
            mapClassNameToHeapClass[heapClassName] = cachedHeapClass
        }
        return cachedHeapClass as HeapObject.HeapClass
    }

    /**
     * @Why("多个className同时分析可能导致引用链查找失败")
     * private fun topTopCountInstancesReferenceQueue(heapGraph: HeapGraph, classNames: List<String>, mapIdToSizes: Map<Long, Int>, topCount: Int) {
     */
    private fun topTopCountInstancesReferenceQueue(heapGraph: HeapGraph, mapIdToNativeSizes: Map<Long, Int>, className: String, topCount: Int) {
        val pathFinder = PathFinder(
                heapGraph,
                OnAnalysisProgressListener.NO_OP,
                AndroidReferenceMatchers.appDefaults
        )

        val instanceIdToInstances = ArrayMap<Long, InstanceInfo>()
        val heapClass = getHeapClassForName(heapGraph, className)

        /**
         * 对所有实例排序
         */
        val allInstancesOfThisClass = ArrayList<HeapObject.HeapInstance>(heapClass.instances.toList())
        for (instance in allInstancesOfThisClass) {
            val instanceId = instance.objectId
            val nativeSize = mapIdToNativeSizes[instanceId]
            val nativeSizeFinal = if (nativeSize is Int) nativeSize else 0
            val shallowSize = calculateShallowSizeOfObjectId(heapGraph, instanceId)
            val retainedSize = nativeSizeFinal + shallowSize
            instanceIdToSizes[instanceId] = InstanceIdWithSizes(instanceId, nativeSizeFinal, shallowSize, retainedSize)
        }
        val allInstanceIdWithSizes = instanceIdToSizes.values.sortedWith(RetainedSizeComparator())

        logger("find ${allInstancesOfThisClass.size} instances of $className")

        /**
         * 找到topTopCount实例的引用链
         */
        allInstanceIdWithSizes.forEachIndexed { idx, instanceIdWithSizes ->
            if (idx > topCount - 1) {
                return@forEachIndexed
            }
            val instanceId = instanceIdWithSizes.objectId
            instanceIdToInstances[instanceId] = InstanceInfo(topIndex = idx, className = className, objectId = instanceIdWithSizes.objectId, nativeSize = instanceIdWithSizes.nativeSize, shallowSize = instanceIdWithSizes.shallowSize, retainedSize = instanceIdWithSizes.retainedSize)
        }

        referenceQueueOfInstance(heapGraph, pathFinder, instanceIdToInstances)
    }

    private fun referenceQueueOfInstance(heapGraph: HeapGraph, pathFinder: PathFinder, instanceIdToInstances: ArrayMap<Long, InstanceInfo>) {
        logger("size of instanceIdToInstances is : ${instanceIdToInstances.size}")
        val hprofInMemoryIndex = (heapGraph as HprofHeapGraph).index

        /**
         * 集中查找所有实例的引用链，单个查找耗时太长
         */
        val pathFindingResults: PathFinder.PathFindingResults = pathFinder.findPathsFromGcRoots(instanceIdToInstances.keys, false)
        logger("size of pathFindingResults is : ${pathFindingResults.pathsToLeakingObjects.size}")
        val shortestPaths: List<HeapAnalyzer.ShortestPath> = deduplicateShortestPaths(pathFindingResults.pathsToLeakingObjects)
        logger("size of shortestPaths is : ${shortestPaths.size}")

        shortestPaths.forEach { shortestPath ->
            val referenceQueue = InstanceInfo.ReferenceQueue<String>()

            shortestPath.childPath.forEachIndexed { idx, childNode ->
                val nodeInstanceId = childNode.objectId
                val instance = heapGraph.findObjectById(nodeInstanceId)
                var instanceName = nameOfHeapObject(instance)
                val nodeOwningClassId = childNode.owningClassId

                if (nodeOwningClassId != 0L) {
                    val owingClassName = hprofInMemoryIndex.className(nodeOwningClassId)
                    instanceName += " | owning by $owingClassName"
                }

                referenceQueue.add("$instanceName \n")

                if (shortestPath.childPath.size - 1 == idx) {
                    for (instanceIdToInstance in instanceIdToInstances) {
                        val objectId = instanceIdToInstance.key
                        val objectIdOfLastNode = shortestPath.childPath[idx].objectId

                        if (objectId == objectIdOfLastNode) {
                            instanceIdToInstances[objectId]?.referenceQueue = referenceQueue
                            break
                        }
                    }
                }
            }
        }

        val allInstanceInfo: List<InstanceInfo> = instanceIdToInstances.values.sortedWith(InstanceInfoComparator())

        for (value in allInstanceInfo) {
            logger(value.toString())
        }
    }

    class InstanceInfo(
            val topIndex: Int,
            var className: String = "",
            var objectId: Long,
            var nativeSize: Int = 0,
            var shallowSize: Int = 0,
            var retainedSize: Int = 0,
            var gcRootCount: Int = 0, // 可达到该实例的gcRoot数量
            var referenceQueue: ReferenceQueue<String> = ReferenceQueue()
    ) {
        override fun toString(): String {
            return "InstanceInfo: topIndex=$topIndex, className='$className', objectId=$objectId, nativeSize=$nativeSize, shallowSize=$shallowSize, retainedSize=$retainedSize, gcRootCount=$gcRootCount, referenceQueue=\n$referenceQueue"
        }

        class ReferenceQueue<E> : LinkedList<E>() {
            override fun toString(): String {
                var result = ""
                forEachIndexed { idx, str ->
                    result += "$Tag:" + getTabsForIdx(idx) + str
                }
                return result
            }
        }
    }
}