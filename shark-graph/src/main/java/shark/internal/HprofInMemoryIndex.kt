package shark.internal

import shark.*
import shark.HprofRecordTag.*
import shark.HprofVersion.ANDROID
import shark.PrimitiveType.INT
import shark.explanation.Doc
import shark.explanation.Important
import shark.internal.IndexedObject.*
import shark.internal.hppc.*
import java.util.*
import kotlin.math.max

/**
 * This class is not thread safe, should be used from a single thread.
 */
class HprofInMemoryIndex private constructor(
        private val positionSize: Int,                              // 存储读完hprof的总byteSize（等于.hprof文件的大小）需要用的字节数
        private val hprofStringCache: LongObjectScatterMap<String>, // 所有stringId - string，包含了.hprof中所有的string，后续的string都根据每个record中的stringId从该map中读取
        private val classNames: LongLongScatterMap,                 // 所有classId - classNameStringId
        private val classIndex: SortedBytesMap,                     // 所有class，每个class先写入的是它的id，按照id排序（方便二分查找）
        private val instanceIndex: SortedBytesMap,                  // 所有instance，每个instance先写入的是它的id，按照id排序（方便二分查找）
        private val objectArrayIndex: SortedBytesMap,               // 所有objectArray，每个objectArray先写入的是它的id，按照id排序（方便二分查找）
        private val primitiveArrayIndex: SortedBytesMap,            // 所有primitiveArray，每个primitiveArray先写入的是它的id，按照id排序（方便二分查找）
        private val gcRoots: List<GcRoot>,                          // 所有gcRoot
        private val proguardMapping: ProguardMapping?,              // 根据mapping.txt生成，解混淆用
        private val bytesForClassSize: Int,                         // 存储每个class需要的字节数，等于存储最大的class需要的字节数（方便直接用下标访问）
        private val bytesForInstanceSize: Int,
        private val bytesForObjectArraySize: Int,
        private val bytesForPrimitiveArraySize: Int,
        private val useForwardSlashClassPackageSeparator: Boolean,  // JVM heap dumps use "/" for package separators (vs "." for Android heap dumps)
        val classFieldsReader: ClassFieldsReader,
        private val classFieldsIndexSize: Int                       // 存储所有class(非instance)成员变量总和的byteSize需要的字节数
) {

    val classCount: Int get() = classIndex.size

    val instanceCount: Int get() = instanceIndex.size

    val objectArrayCount: Int get() = objectArrayIndex.size

    val primitiveArrayCount: Int get() = primitiveArrayIndex.size

    fun fieldName(classId: Long, id: Long): String {
        val fieldNameString = hprofStringById(id)
        return proguardMapping?.let {
            val classNameStringId = classNames[classId]
            val classNameString = hprofStringById(classNameStringId)
            proguardMapping.deobfuscateFieldName(classNameString, fieldNameString)
        } ?: fieldNameString
    }

    @Doc("根据classId获取className")
    fun className(classId: Long): String {
        // String, primitive types
        val classNameStringId = classNames[classId]
        val classNameString = hprofStringById(classNameStringId)
        return (proguardMapping?.deobfuscateClassName(classNameString) ?: classNameString).run {
            if (useForwardSlashClassPackageSeparator) {
                // JVM heap dumps use "/" for package separators (vs "." for Android heap dumps)
                replace('/', '.')
            } else this
        }
    }

    fun classId(className: String): Long? {
        val internalClassName = if (useForwardSlashClassPackageSeparator) {
            // JVM heap dumps use "/" for package separators (vs "." for Android heap dumps)
            className.replace('.', '/')
        } else className

        // Note: this performs two linear scans over arrays
        val hprofStringId = hprofStringCache.entrySequence()
                .firstOrNull { it.second == internalClassName }
                ?.first
        return hprofStringId?.let { stringId ->
            classNames.entrySequence()
                    .firstOrNull { it.second == stringId }
                    ?.first
        }
    }

    fun indexedClassSequence(): Sequence<LongObjectPair<IndexedClass>> {
        return classIndex.entrySequence()
                .map {
                    val id = it.first
                    val array = it.second
                    id to array.readClass()
                }
    }

    @Doc("将ByteSubArray转化为IndexedInstance")
    fun indexedInstanceSequence(): Sequence<LongObjectPair<IndexedInstance>> {
        return instanceIndex.entrySequence()
                .map {
                    val id = it.first // INSTANCE ID
                    val array = it.second
                    val instance = IndexedInstance(
                            position = array.readTruncatedLong(positionSize),
                            classId = array.readId(), // CLASS ID
                            recordSize = array.readTruncatedLong(bytesForInstanceSize)
                    )
                    id to instance
                }
    }

    fun indexedObjectArraySequence(): Sequence<LongObjectPair<IndexedObjectArray>> {
        return objectArrayIndex.entrySequence()
                .map {
                    val id = it.first
                    val array = it.second
                    val objectArray = IndexedObjectArray(
                            position = array.readTruncatedLong(positionSize),
                            arrayClassId = array.readId(),
                            recordSize = array.readTruncatedLong(bytesForObjectArraySize)
                    )
                    id to objectArray
                }
    }

    fun indexedPrimitiveArraySequence(): Sequence<LongObjectPair<IndexedPrimitiveArray>> {
        return primitiveArrayIndex.entrySequence()
                .map {
                    val id = it.first
                    val array = it.second

                    val primitiveArray = IndexedPrimitiveArray(
                            position = array.readTruncatedLong(positionSize),
                            primitiveType = PrimitiveType.values()[array.readByte()
                                    .toInt()],
                            recordSize = array.readTruncatedLong(bytesForPrimitiveArraySize)
                    )
                    id to primitiveArray
                }
    }

    fun indexedObjectSequence(): Sequence<LongObjectPair<IndexedObject>> {
        return indexedClassSequence() +
                indexedInstanceSequence() +
                indexedObjectArraySequence() +
                indexedPrimitiveArraySequence()
    }

    fun gcRoots(): List<GcRoot> {
        return gcRoots
    }

    fun objectAtIndex(index: Int): LongObjectPair<IndexedObject> {
        require(index > 0)
        if (index < classIndex.size) {
            val objectId = classIndex.keyAt(index)
            val array = classIndex.getAtIndex(index)
            return objectId to array.readClass()
        }
        var shiftedIndex = index - classIndex.size
        if (shiftedIndex < instanceIndex.size) {
            val objectId = instanceIndex.keyAt(shiftedIndex)
            val array = instanceIndex.getAtIndex(shiftedIndex)
            return objectId to IndexedInstance(
                    position = array.readTruncatedLong(positionSize),
                    classId = array.readId(),
                    recordSize = array.readTruncatedLong(bytesForInstanceSize)
            )
        }
        shiftedIndex -= instanceIndex.size
        if (shiftedIndex < objectArrayIndex.size) {
            val objectId = objectArrayIndex.keyAt(shiftedIndex)
            val array = objectArrayIndex.getAtIndex(shiftedIndex)
            return objectId to IndexedObjectArray(
                    position = array.readTruncatedLong(positionSize),
                    arrayClassId = array.readId(),
                    recordSize = array.readTruncatedLong(bytesForObjectArraySize)
            )
        }
        shiftedIndex -= objectArrayIndex.size
        require(index < primitiveArrayIndex.size)
        val objectId = primitiveArrayIndex.keyAt(shiftedIndex)
        val array = primitiveArrayIndex.getAtIndex(shiftedIndex)
        return objectId to IndexedPrimitiveArray(
                position = array.readTruncatedLong(positionSize),
                primitiveType = PrimitiveType.values()[array.readByte()
                        .toInt()],
                recordSize = array.readTruncatedLong(bytesForPrimitiveArraySize)
        )
    }

    @Suppress("ReturnCount")
    fun indexedObjectOrNull(objectId: Long): IntObjectPair<IndexedObject>? {
        var index = classIndex.indexOf(objectId)
        if (index >= 0) {
            val array = classIndex.getAtIndex(index)
            return index to array.readClass()
        }
        index = instanceIndex.indexOf(objectId)
        if (index >= 0) {
            val array = instanceIndex.getAtIndex(index)
            return classIndex.size + index to IndexedInstance(
                    position = array.readTruncatedLong(positionSize),
                    classId = array.readId(),
                    recordSize = array.readTruncatedLong(bytesForInstanceSize)
            )
        }
        index = objectArrayIndex.indexOf(objectId)
        if (index >= 0) {
            val array = objectArrayIndex.getAtIndex(index)
            return classIndex.size + instanceIndex.size + index to IndexedObjectArray(
                    position = array.readTruncatedLong(positionSize),
                    arrayClassId = array.readId(),
                    recordSize = array.readTruncatedLong(bytesForObjectArraySize)
            )
        }
        index = primitiveArrayIndex.indexOf(objectId)
        if (index >= 0) {
            val array = primitiveArrayIndex.getAtIndex(index)
            return classIndex.size + instanceIndex.size + index + primitiveArrayIndex.size to IndexedPrimitiveArray(
                    position = array.readTruncatedLong(positionSize),
                    primitiveType = PrimitiveType.values()[array.readByte()
                            .toInt()],
                    recordSize = array.readTruncatedLong(bytesForPrimitiveArraySize)
            )
        }
        return null
    }

    private fun ByteSubArray.readClass(): IndexedClass {
        val position = readTruncatedLong(positionSize)
        val superclassId = readId()
        val instanceSize = readInt()

        val recordSize = readTruncatedLong(bytesForClassSize)
        val fieldsIndex = readTruncatedLong(classFieldsIndexSize).toInt()

        return IndexedClass(
                position = position,
                superclassId = superclassId,
                instanceSize = instanceSize,
                recordSize = recordSize,
                fieldsIndex = fieldsIndex
        )
    }

    @Suppress("ReturnCount")
    fun objectIdIsIndexed(objectId: Long): Boolean {
        if (classIndex[objectId] != null) {
            return true
        }
        if (instanceIndex[objectId] != null) {
            return true
        }
        if (objectArrayIndex[objectId] != null) {
            return true
        }
        if (primitiveArrayIndex[objectId] != null) {
            return true
        }
        return false
    }

    private fun hprofStringById(id: Long): String {
        return hprofStringCache[id] ?: throw IllegalArgumentException("Hprof string $id not in cache")
    }

    private class Builder(
            longIdentifiers: Boolean,
            maxPosition: Long,
            classCount: Int,
            instanceCount: Int,
            objectArrayCount: Int,
            primitiveArrayCount: Int,
            val bytesForClassSize: Int,
            val bytesForInstanceSize: Int,
            val bytesForObjectArraySize: Int,
            val bytesForPrimitiveArraySize: Int,
            val classFieldsTotalBytes: Int
    ) : OnHprofRecordTagListener {

        private val identifierSize = if (longIdentifiers) 8 else 4
        private val positionSize = byteSizeForUnsigned(maxPosition)
        private val classFieldsIndexSize = byteSizeForUnsigned(classFieldsTotalBytes.toLong())

        /**
         * Map of string id to string
         * This currently keeps all the hprof strings that we could care about: class names,
         * static field names and instance fields names
         */
        // TODO Replacing with a radix trie reversed into a sparse array of long to trie leaf could save
        // memory. Can be stored as 3 arrays: array of keys, array of values which are indexes into
        // a large array of string bytes. Each "entry" consists of a size, the index of the previous
        // segment and then the segment content.

        private val hprofStringCache = LongObjectScatterMap<String>()

        /**
         * class id to string id
         */
        val classNames = LongLongScatterMap(expectedElements = classCount)

        val classFieldBytes = ByteArray(classFieldsTotalBytes)

        var classFieldsIndex = 0

        val unsortedByteEntriesOfClass = UnsortedByteEntries(
                bytesPerValue = positionSize + identifierSize + 4 + bytesForClassSize + classFieldsIndexSize,
                longIdentifiers = longIdentifiers,
                initialCapacity = classCount
        )
        val unsortedByteEntriesOfInstance = UnsortedByteEntries(
                bytesPerValue = positionSize + identifierSize + bytesForInstanceSize,
                longIdentifiers = longIdentifiers,
                initialCapacity = instanceCount
        )
        val unsortedByteEntriesOfObjectArray = UnsortedByteEntries(
                bytesPerValue = positionSize + identifierSize + bytesForObjectArraySize,
                longIdentifiers = longIdentifiers,
                initialCapacity = objectArrayCount
        )
        val unsortedByteEntriesOfPrimitiveArray = UnsortedByteEntries(
                bytesPerValue = positionSize + 1 + bytesForPrimitiveArraySize,
                longIdentifiers = longIdentifiers,
                initialCapacity = primitiveArrayCount
        )

        val gcRoots = mutableListOf<GcRoot>()

        private fun HprofRecordReader.copyToClassFields(byteCount: Int) {
            for (i in 1..byteCount) {
                classFieldBytes[classFieldsIndex++] = readByte()
            }
        }

        private fun lastClassFieldsShort() =
                ((classFieldBytes[classFieldsIndex - 2].toInt() and 0xff shl 8) or
                        (classFieldBytes[classFieldsIndex - 1].toInt() and 0xff)).toShort()

        @Suppress("LongMethod")
        override fun onHprofRecord(
                tag: HprofRecordTag,
                length: Long,
                reader: HprofRecordReader
        ) {
            when (tag) {
                STRING_IN_UTF8 -> {
                    val id = reader.readId()
                    val value = reader.readUtf8(length - identifierSize)
                    hprofStringCache[id] = value
                }
                LOAD_CLASS -> {
                    // classSerialNumber
                    reader.skip(INT.byteSize)
                    @Important("3.该id是Class对象的id")
                    val id = reader.readId()
                    // stackTraceSerialNumber
                    reader.skip(INT.byteSize)
                    @Doc("该className的id(该id总在hprofStringCache中存在)")
                    val classNameStringId = reader.readId()
                    classNames[id] = classNameStringId
                }
                ROOT_UNKNOWN -> {
                    reader.readUnknownGcRootRecord().apply {
                        if (id != ValueHolder.NULL_REFERENCE) {
                            gcRoots += this
                        }
                    }
                }
                ROOT_JNI_GLOBAL -> {
                    reader.readJniGlobalGcRootRecord().apply {
                        if (id != ValueHolder.NULL_REFERENCE) {
                            gcRoots += this
                        }
                    }
                }
                ROOT_JNI_LOCAL -> {
                    reader.readJniLocalGcRootRecord().apply {
                        if (id != ValueHolder.NULL_REFERENCE) {
                            gcRoots += this
                        }
                    }
                }
                ROOT_JAVA_FRAME -> {
                    reader.readJavaFrameGcRootRecord().apply {
                        if (id != ValueHolder.NULL_REFERENCE) {
                            gcRoots += this
                        }
                    }
                }
                ROOT_NATIVE_STACK -> {
                    reader.readNativeStackGcRootRecord().apply {
                        if (id != ValueHolder.NULL_REFERENCE) {
                            gcRoots += this
                        }
                    }
                }
                ROOT_STICKY_CLASS -> {
                    reader.readStickyClassGcRootRecord().apply {
                        if (id != ValueHolder.NULL_REFERENCE) {
                            gcRoots += this
                        }
                    }
                }
                ROOT_THREAD_BLOCK -> {
                    reader.readThreadBlockGcRootRecord().apply {
                        if (id != ValueHolder.NULL_REFERENCE) {
                            gcRoots += this
                        }
                    }
                }
                ROOT_MONITOR_USED -> {
                    reader.readMonitorUsedGcRootRecord().apply {
                        if (id != ValueHolder.NULL_REFERENCE) {
                            gcRoots += this
                        }
                    }
                }
                ROOT_THREAD_OBJECT -> {
                    reader.readThreadObjectGcRootRecord().apply {
                        if (id != ValueHolder.NULL_REFERENCE) {
                            gcRoots += this
                        }
                    }
                }
                ROOT_INTERNED_STRING -> {
                    reader.readInternedStringGcRootRecord().apply {
                        if (id != ValueHolder.NULL_REFERENCE) {
                            gcRoots += this
                        }
                    }
                }
                ROOT_FINALIZING -> {
                    reader.readFinalizingGcRootRecord().apply {
                        if (id != ValueHolder.NULL_REFERENCE) {
                            gcRoots += this
                        }
                    }
                }
                ROOT_DEBUGGER -> {
                    reader.readDebuggerGcRootRecord().apply {
                        if (id != ValueHolder.NULL_REFERENCE) {
                            gcRoots += this
                        }
                    }
                }
                ROOT_REFERENCE_CLEANUP -> {
                    reader.readReferenceCleanupGcRootRecord().apply {
                        if (id != ValueHolder.NULL_REFERENCE) {
                            gcRoots += this
                        }
                    }
                }
                ROOT_VM_INTERNAL -> {
                    reader.readVmInternalGcRootRecord().apply {
                        if (id != ValueHolder.NULL_REFERENCE) {
                            gcRoots += this
                        }
                    }
                }
                ROOT_JNI_MONITOR -> {
                    reader.readJniMonitorGcRootRecord().apply {
                        if (id != ValueHolder.NULL_REFERENCE) {
                            gcRoots += this
                        }
                    }
                }
                ROOT_UNREACHABLE -> {
                    reader.readUnreachableGcRootRecord().apply {
                        if (id != ValueHolder.NULL_REFERENCE) {
                            gcRoots += this
                        }
                    }
                }
                CLASS_DUMP -> {
                    val bytesReadStart = reader.bytesRead

                    @Important("3.该id是Class对象的id")
                    val id = reader.readId()
//                    recordId2type(id, "CLASS_DUMP")
                    // stack trace serial number
                    reader.skip(INT.byteSize)
                    val superclassId = reader.readId()
                    reader.skip(5 * identifierSize)

                    // instance size (in bytes)
                    // Useful to compute retained size
                    val instanceSize = reader.readInt()

                    reader.skipClassDumpConstantPool()

                    val startPosition = classFieldsIndex

                    val bytesReadFieldStart = reader.bytesRead

                    reader.copyToClassFields(2)
                    val staticFieldCount = lastClassFieldsShort().toInt() and 0xFFFF
                    for (i in 0 until staticFieldCount) {
                        reader.copyToClassFields(identifierSize)
                        reader.copyToClassFields(1)
                        val type = classFieldBytes[classFieldsIndex - 1].toInt() and 0xff
                        if (type == PrimitiveType.REFERENCE_HPROF_TYPE) {
                            reader.copyToClassFields(identifierSize)
                        } else {
                            reader.copyToClassFields(PrimitiveType.byteSizeByHprofType.getValue(type))
                        }
                    }

                    reader.copyToClassFields(2)
                    val fieldCount = lastClassFieldsShort().toInt() and 0xFFFF
                    for (i in 0 until fieldCount) {
                        reader.copyToClassFields(identifierSize)
                        reader.copyToClassFields(1)
                    }

                    val fieldsSize = (reader.bytesRead - bytesReadFieldStart).toInt()
                    val recordSize = reader.bytesRead - bytesReadStart

                    unsortedByteEntriesOfClass.append(id)
                            .apply {
                                writeTruncatedLong(bytesReadStart, positionSize)
                                writeId(superclassId)
                                writeInt(instanceSize)
                                writeTruncatedLong(recordSize, bytesForClassSize)
                                writeTruncatedLong(startPosition.toLong(), classFieldsIndexSize)
                            }
                    require(startPosition + fieldsSize == classFieldsIndex) {
                        "Expected $classFieldsIndex to have moved by $fieldsSize and be equal to ${startPosition + fieldsSize}"
                    }
                }
                INSTANCE_DUMP -> {
                    val bytesReadStart = reader.bytesRead
                    val id = reader.readId()
                    reader.skip(INT.byteSize)
                    @Important("3.该id是Class对象的id")
                    val classId = reader.readId()
                    val remainingBytesInInstance = reader.readInt()
                    reader.skip(remainingBytesInInstance)
                    val recordSize = reader.bytesRead - bytesReadStart
                    unsortedByteEntriesOfInstance.append(id)
                            .apply {
                                writeTruncatedLong(bytesReadStart, positionSize)
                                writeId(classId)
                                writeTruncatedLong(recordSize, bytesForInstanceSize)
                            }
                }
                OBJECT_ARRAY_DUMP -> {
                    val bytesReadStart = reader.bytesRead
                    val id = reader.readId()
                    reader.skip(INT.byteSize)
                    val size = reader.readInt()
                    val arrayClassId = reader.readId()
                    reader.skip(identifierSize * size)
                    val recordSize = reader.bytesRead - bytesReadStart
                    unsortedByteEntriesOfObjectArray.append(id)
                            .apply {
                                writeTruncatedLong(bytesReadStart, positionSize)
                                writeId(arrayClassId)
                                writeTruncatedLong(recordSize, bytesForObjectArraySize)
                            }
                }
                PRIMITIVE_ARRAY_DUMP -> {
                    val bytesReadStart = reader.bytesRead
                    val id = reader.readId()
                    reader.skip(INT.byteSize)
                    val size = reader.readInt()
                    val type = PrimitiveType.primitiveTypeByHprofType.getValue(reader.readUnsignedByte())
                    reader.skip(size * type.byteSize)
                    val recordSize = reader.bytesRead - bytesReadStart
                    unsortedByteEntriesOfPrimitiveArray.append(id)
                            .apply {
                                writeTruncatedLong(bytesReadStart, positionSize)
                                writeByte(type.ordinal.toByte())
                                writeTruncatedLong(recordSize, bytesForPrimitiveArraySize)
                            }
                }
            }
        }

        fun buildIndex(
                proguardMapping: ProguardMapping?,
                hprofHeader: HprofHeader
        ): HprofInMemoryIndex {
            require(classFieldsIndex == classFieldBytes.size) {
                "Read $classFieldsIndex into fields bytes instead of expected ${classFieldBytes.size}"
            }

            val sortedInstanceIndex = unsortedByteEntriesOfInstance.moveToSortedMap()
            val sortedObjectArrayIndex = unsortedByteEntriesOfObjectArray.moveToSortedMap()
            val sortedPrimitiveArrayIndex = unsortedByteEntriesOfPrimitiveArray.moveToSortedMap()
            val sortedClassIndex = unsortedByteEntriesOfClass.moveToSortedMap()
            // Passing references to avoid copying the underlying data structures.
            println("sizeOfVisitAndVisiting >> gcRoot >> :${gcRoots.size}")
            return HprofInMemoryIndex(
                    positionSize = positionSize,
                    hprofStringCache = hprofStringCache,
                    classNames = classNames,
                    classIndex = sortedClassIndex,
                    instanceIndex = sortedInstanceIndex,
                    objectArrayIndex = sortedObjectArrayIndex,
                    primitiveArrayIndex = sortedPrimitiveArrayIndex,
                    gcRoots = gcRoots,
                    proguardMapping = proguardMapping,
                    bytesForClassSize = bytesForClassSize,
                    bytesForInstanceSize = bytesForInstanceSize,
                    bytesForObjectArraySize = bytesForObjectArraySize,
                    bytesForPrimitiveArraySize = bytesForPrimitiveArraySize,
                    useForwardSlashClassPackageSeparator = hprofHeader.version != ANDROID,
                    classFieldsReader = ClassFieldsReader(identifierSize, classFieldBytes),
                    classFieldsIndexSize = classFieldsIndexSize
            )
        }
    }

    companion object {

        /*
         * 计算需要几个字节存储该Long
         */
        private fun byteSizeForUnsigned(maxValue: Long): Int {
//            println("byteSizeForUnsigned >> binaryMaxValue:${maxValue.toString(2)}")
            var value = maxValue
            var byteCount = 0
            while (value != 0L) {
                /*
                 * 有符号右移8位
                 */
                value = value shr 8
                byteCount++
            }
            return byteCount
        }

        fun indexHprof(
                reader: StreamingHprofReader,
                hprofHeader: HprofHeader,
                proguardMapping: ProguardMapping?,
                indexedGcRootTags: Set<HprofRecordTag>
        ): HprofInMemoryIndex {

            // First pass to count and correctly size arrays once and for all.
            var maxClassSize = 0L
            var maxInstanceSize = 0L
            var maxObjectArraySize = 0L
            var maxPrimitiveArraySize = 0L
            var classCount = 0
            var instanceCount = 0
            var objectArrayCount = 0
            var primitiveArrayCount = 0
            var classFieldsTotalBytes = 0

            var stringCount = 0

            @Doc("3.第一次调用reader.readRecords来统计所有" +
                    "- 含有静态变量的对象的数量" +
                    "- 所有静态成员变量总的byteCount")
//            var canTagTotalCount = 0
            val bytesRead = reader.readRecords(
                    EnumSet.of(CLASS_DUMP, INSTANCE_DUMP, OBJECT_ARRAY_DUMP, PRIMITIVE_ARRAY_DUMP),
                    OnHprofRecordTagListener { tag, _, reader ->
//                        if (++canTagTotalCount <= 20)
//                            println("tag:$tag")

                        val bytesReadStart = reader.bytesRead
                        when (tag) {
                            CLASS_DUMP -> {
                                classCount++
                                reader.skipClassDumpHeader()
                                val bytesReadStaticFieldStart = reader.bytesRead
                                reader.skipClassDumpStaticFields()
                                reader.skipClassDumpFields()
                                maxClassSize = max(maxClassSize, reader.bytesRead - bytesReadStart)
                                @Doc("统计所有类的所有成员变量总大小")
                                classFieldsTotalBytes += (reader.bytesRead - bytesReadStaticFieldStart).toInt()
                            }
                            INSTANCE_DUMP -> {
                                instanceCount++
                                reader.skipInstanceDumpRecord()
                                maxInstanceSize = max(maxInstanceSize, reader.bytesRead - bytesReadStart)
                            }
                            OBJECT_ARRAY_DUMP -> {
                                objectArrayCount++
                                reader.skipObjectArrayDumpRecord()
                                maxObjectArraySize = max(maxObjectArraySize, reader.bytesRead - bytesReadStart)
                            }
                            PRIMITIVE_ARRAY_DUMP -> {
                                primitiveArrayCount++
                                reader.skipPrimitiveArrayDumpRecord()
                                maxPrimitiveArraySize = max(maxPrimitiveArraySize, reader.bytesRead - bytesReadStart)
                            }
                        }
                    })

            @Doc("8.这里获取到hprof中每个record类型占用的最大byteCount，方便后续直接根据下标访问，提高效率")
            val bytesForClassSize = byteSizeForUnsigned(maxClassSize)
            val bytesForInstanceSize = byteSizeForUnsigned(maxInstanceSize)
            val bytesForObjectArraySize = byteSizeForUnsigned(maxObjectArraySize)
            val bytesForPrimitiveArraySize = byteSizeForUnsigned(maxPrimitiveArraySize)
            val indexBuilderListener = Builder(
                    longIdentifiers = hprofHeader.identifierByteSize == 8,
                    maxPosition = bytesRead,
                    classCount = classCount,
                    instanceCount = instanceCount,
                    objectArrayCount = objectArrayCount,
                    primitiveArrayCount = primitiveArrayCount,
                    bytesForClassSize = bytesForClassSize,
                    bytesForInstanceSize = bytesForInstanceSize,
                    bytesForObjectArraySize = bytesForObjectArraySize,
                    bytesForPrimitiveArraySize = bytesForPrimitiveArraySize,
                    classFieldsTotalBytes = classFieldsTotalBytes
            )
//            println("bytesRead:$bytesRead")
//            PrettyLogger.commonLog(classCount, instanceCount, objectArrayCount, primitiveArrayCount)
            val recordTypes = EnumSet.of(
                    STRING_IN_UTF8,
                    LOAD_CLASS,
                    CLASS_DUMP,
                    INSTANCE_DUMP,
                    OBJECT_ARRAY_DUMP,
                    PRIMITIVE_ARRAY_DUMP
            ) + HprofRecordTag.rootTags.intersect(indexedGcRootTags)

            @Doc("3.第二次调用reader.readRecords，回调给indexBuilderListener")
            reader.readRecords(recordTypes, indexBuilderListener)

            println("classNames:${indexBuilderListener.classNames.size} unsortedByteEntriesOfClass:${indexBuilderListener.unsortedByteEntriesOfClass.assigned}")
//            indexBuilderListener.logId2String()
//            checkIds(indexBuilderListener)
            return indexBuilderListener.buildIndex(proguardMapping, hprofHeader)
        }

        @Important("4.CLASS_DUMP(a)和LOAD_CLASS(b)下的id数量相等，它包含的是hprof中所有的类对象的数量，" +
                "INSTANCE_DUMP下的第一个id(c)则是当前对象实例的id，因此 a == b <<= c " +
                "而INSTANCE_DUMP下的第二个id(d)则是当前对象实例的类对象的id，因此 a == b >= d ")
        private fun checkIds(builder: Builder) {
//            val instanceDumps = allKeyValues["INSTANCE_DUMP"]
//            val stringCache = allKeyValues["STRING_IN_UTF8"]
//            stringCache?.let { strCache ->
//                println("stringCache >>>>>>>>>>>>>>>>>")
//                instanceDumps?.keys?.run {
//                    forEachIndexed { _, l ->
//
//                    }
//                }
//            }
        }
    }
}