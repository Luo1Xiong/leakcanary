package leakcanary

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import leakcanary.Profiler.runWithProfilerSampling
import org.junit.Ignore
import org.junit.Test
import shark.*
import shark.OnAnalysisProgressListener.Step
import java.io.File
import java.io.FileOutputStream

class ProfiledTest {

    @Ignore
    @Test
    fun analyzeLargeDump() {
        profileAnalysis("large-dump.hprof")
    }

    private fun profileAnalysis(fileName: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        val heapDumpFile = File(context.filesDir, "ProfiledTest.hprof")
        context.assets.open(fileName)
                .copyTo(FileOutputStream(heapDumpFile))

        runWithProfilerSampling {
            val analyzer = HeapAnalyzer(object : OnAnalysisProgressListener {
                override fun onAnalysisProgress(step: Step) {
                    Log.d("LeakCanary", step.name)
                }
            })
            val result = analyzer.analyze(
                    heapDumpFile = heapDumpFile,
                    leakingObjectFinder = KeyedWeakReferenceFinder,
                    referenceMatchers = AndroidReferenceMatchers.appDefaults,
                    objectInspectors = AndroidObjectInspectors.appDefaults,
                    computeRetainedHeapSize = true
            )
            SharkLog.d { result.toString() }
        }
    }
}

