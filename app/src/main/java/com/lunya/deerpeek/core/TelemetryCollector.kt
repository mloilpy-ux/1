import android.app.usage.UsageStatsManager
import java.util.SortedMap
import java.util.TreeMap

fun getForegroundPackage(): String {
    val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val time = System.currentTimeMillis()
    val usageStats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 60, time)
    
    if (usageStats != null && usageStats.isNotEmpty()) {
        val sortedMap: SortedMap<Long, android.app.usage.UsageStats> = TreeMap()
        for (usageStat in usageStats) {
            sortedMap[usageStat.lastTimeUsed] = usageStat
        }
        if (!sortedMap.isEmpty()) {
            return sortedMap[sortedMap.lastKey()]?.packageName ?: "unknown"
        }
    }
    return "launcher_or_restricted"
}
