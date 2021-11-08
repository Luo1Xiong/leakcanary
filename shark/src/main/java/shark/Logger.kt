package shark

class Logger {
    companion object{
        private const val StatisticsTag = "hprofStatistics"
        private const val AnalyzeFlowTag = "hprofAnalyzeFlowTag"

        fun logStatistics(str: String) {
            println("$StatisticsTag:$str")
        }

        fun logAnalyzeFlow(str: String) {
            println("$AnalyzeFlowTag:$str")
        }
    }
}