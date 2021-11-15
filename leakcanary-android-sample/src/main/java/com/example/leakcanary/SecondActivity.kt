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
import com.example.leakcanary.HprofUtility.Companion.getHprofUtility
import shark.*
import shark.HeapAnalyzer.Companion.deduplicateShortestPaths
import shark.Logger.Companion.logStatistics
import shark.explanation.ForTest
import shark.internal.IndexedObject
import shark.internal.PathFinder
import shark.internal.ShallowSizeCalculator
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.*
import kotlin.collections.ArrayList

class SecondActivity : Activity() {

    companion object {
        @ForTest
        var cachedImageView: ImageView? = null
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
    }

    private lateinit var mImageViewHprof: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.second_activity)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            requirePermissions()
        mImageViewHprof = findViewById(R.id.iv_test)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mImageViewHprof.setImageDrawable(resources.getDrawable(R.drawable.test, null))
            cachedImageView = mImageViewHprof
        }
        findViewById<Button>(R.id.analyze).setOnClickListener {
            analyzeHprof()
        }

        findViewById<Button>(R.id.create_bitmap).setOnClickListener {
            createBitmapFromHprof()
        }

        findViewById<Button>(R.id.find_all_bitmaps).setOnClickListener {
            findInstancesOfClass()
        }

        findViewById<Button>(R.id.reference_queue_for_id).setOnClickListener {
            findSpecificReference()
        }
    }

    private val permissionRequestCode = 10000

    @RequiresApi(Build.VERSION_CODES.M)
    private fun requirePermissions() {
        if (ContextCompat.checkSelfPermission(this@SecondActivity, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this@SecondActivity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), permissionRequestCode);
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

        print(sharkResult)
        @ForTest
        logStatistics(Arrays.toString(AndroidObjectInspectors.checkingViewIds.toArray()))
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


    private fun findSpecificReference() {
        val hprofUtility = getHprofUtility()

        val viewIds = Cache.leakingIds
        val instanceList = ArrayList<HeapObject.HeapInstance>()
        for (viewId in viewIds) {
            instanceList.add(hprofUtility.heapGraph.findObjectById(viewId) as HeapObject.HeapInstance)
        }
        referenceQueues(hprofUtility.heapGraph, hprofUtility.pathFinder, null, hprofUtility.mapIdToNativeSizes, instanceList)
    }

    private fun findInstancesOfClass() {
        val hprofUtility = getHprofUtility()

        val className = "android.graphics.Bitmap"
        val heapClass = getHeapClassForName(hprofUtility.heapGraph, className)
        val instanceList = ArrayList<HeapObject.HeapInstance>(heapClass.instances.toList())
        logStatistics("find ${instanceList.size} instances of $className")

        referenceQueues(hprofUtility.heapGraph, hprofUtility.pathFinder, className, hprofUtility.mapIdToNativeSizes, instanceList, 30, true)
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
    private fun referenceQueues(heapGraph: HeapGraph, pathFinder: PathFinder, className: String?, mapIdToNativeSizes: Map<Long, Int>, heapInstanceList: ArrayList<HeapObject.HeapInstance>, topCount: Int = -1, needSort: Boolean = false) {
        val instanceIdToInstances = ArrayMap<Long, InstanceInfo>()

        for (instance in heapInstanceList) {
            val instanceId = instance.objectId
            val nativeSize = mapIdToNativeSizes[instanceId]
            val nativeSizeFinal = if (nativeSize is Int) nativeSize else 0
            val shallowSize = calculateShallowSizeOfObjectId(heapGraph, instanceId)
            val retainedSize = nativeSizeFinal + shallowSize
            instanceIdToSizes[instanceId] = InstanceIdWithSizes(instanceId, nativeSizeFinal, shallowSize, retainedSize)
        }

        /**
         * 对所有实例排序
         */
        val allInstanceIdWithSizes: ArrayList<InstanceIdWithSizes> = if (needSort)
            ArrayList<InstanceIdWithSizes>().apply { addAll(instanceIdToSizes.values.sortedWith(RetainedSizeComparator())) }
        else
            ArrayList<InstanceIdWithSizes>().apply { addAll(instanceIdToSizes.values) }

        /**
         * 找到topTopCount实例的引用链
         */
        allInstanceIdWithSizes.forEachIndexed { idx, instanceIdWithSizes ->
            if (topCount != -1 && idx > topCount - 1) {
                return@forEachIndexed
            }
            val instanceId = instanceIdWithSizes.objectId
            var realClassName = className
            if (realClassName !is String) {
                val classId = ((heapGraph as HprofHeapGraph).index.indexedObjectOrNull(instanceId)?.second as IndexedObject.IndexedInstance).classId
                realClassName = heapGraph.index.className(classId)
            }
            instanceIdToInstances[instanceId] = InstanceInfo(topIndex = idx, className = realClassName, objectId = instanceIdWithSizes.objectId, nativeSize = instanceIdWithSizes.nativeSize, shallowSize = instanceIdWithSizes.shallowSize, retainedSize = instanceIdWithSizes.retainedSize)
        }

        referenceQueueOfInstance(heapGraph, pathFinder, instanceIdToInstances)
    }

    private fun referenceQueueOfInstance(heapGraph: HeapGraph, pathFinder: PathFinder, instanceIdToInstances: ArrayMap<Long, InstanceInfo>) {
        logStatistics("size of instanceIdToInstances is : ${instanceIdToInstances.size}")
        val hprofInMemoryIndex = (heapGraph as HprofHeapGraph).index

        /**
         * 集中查找所有实例的引用链，单个查找耗时太长
         */
        val pathFindingResults: PathFinder.PathFindingResults = pathFinder.findPathsFromGcRoots(instanceIdToInstances.keys, true)

        /**
         * 检查支配树里的节点之间的支配关系
         */
//        var checkCount = 0
//        pathFindingResults.dominatorTree?.dominated?.forEach(object : LongLongScatterMap.ForEachCallback {
//            override fun onEntry(key: Long, value: Long) {
//                if (++checkCount < 100) {
//                    val keyName = nameOfHeapObject(heapGraph.findObjectById(key))
//                    val valueName = nameOfHeapObject(heapGraph.findObjectById(value))
//                    println("$keyName dominated by $valueName")
//                }
//            }
//        })

        logStatistics("size of pathFindingResults is : ${pathFindingResults.pathsToLeakingObjects.size}")
        val shortestPaths: List<HeapAnalyzer.ShortestPath> = deduplicateShortestPaths(pathFindingResults.pathsToLeakingObjects)
        logStatistics("size of shortestPaths is : ${shortestPaths.size}")

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
            logStatistics(value.toString())
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
            return "leakingInfo: className='$className', objectId=$objectId, nativeSize=$nativeSize, shallowSize=$shallowSize, retainedSize=$retainedSize, gcRootCount=$gcRootCount, referenceQueue=\n$referenceQueue"
        }

        class ReferenceQueue<E> : LinkedList<E>() {
            override fun toString(): String {
                var result = ""
                forEachIndexed { idx, str ->
                    result += "hprofStatistics:" + getTabsForIdx(idx) + str
                }
                return result
            }
        }
    }
}