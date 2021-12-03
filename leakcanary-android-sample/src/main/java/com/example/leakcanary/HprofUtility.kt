package com.example.leakcanary

import shark.*
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.internal.AndroidNativeSizeMapper
import shark.internal.PathFinder
import java.io.File
import java.io.FileInputStream

class HprofUtility private constructor(var heapGraph: HeapGraph, var mapIdToNativeSizes: Map<Long, Int>, var pathFinder: PathFinder) {

    companion object {
        private var gHprofUtility: HprofUtility? = null

        private fun buildHprofUtility(
                hprofFile: File,
                mappingFile: File?
        ): HprofUtility {
            PathFinder.resetAll()
            var mapping: ProguardMapping? = null
            if (mappingFile is File && mappingFile.exists()) {
                val proguardMappingInputStream = FileInputStream(mappingFile)
                val proguardMappingReader = ProguardMappingReader(proguardMappingInputStream)
                mapping = proguardMappingReader.readProguardMapping()
            }
            val heapGraph = hprofFile.openHeapGraph(mapping)

            /**
             * 该方法耗时长，需要缓存起来
             */
            val mapIdToNativeSizes = AndroidNativeSizeMapper(heapGraph).mapNativeSizes()

            val pathFinder = PathFinder(
                    heapGraph,
                    OnAnalysisProgressListener.NO_OP,
                    AndroidReferenceMatchers.appDefaults
            )

            return HprofUtility(heapGraph, mapIdToNativeSizes, pathFinder)
        }

        fun getHprofUtility(): HprofUtility {
            if (gHprofUtility is HprofUtility) return gHprofUtility as HprofUtility
            val hprofFile = File("/sdcard/testHprofBitmap.hprof")
            val mappingFile = File("/sdcard/mapping.txt")
            return buildHprofUtility(hprofFile, mappingFile)
        }
    }

}