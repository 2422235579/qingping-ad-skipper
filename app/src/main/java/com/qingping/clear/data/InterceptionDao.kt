package com.qingping.clear.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * 一条按APP聚合的统计结果
 */
data class AppStats(
    val packageName: String,
    val totalCount: Int,
    val splashCount: Int,
    val jumpCount: Int
)

@Dao
interface InterceptionDao {

    @Insert
    suspend fun insert(record: InterceptionRecord)

    /** 总拦截次数 */
    @Query("SELECT COUNT(*) FROM interception_records")
    fun getTotalCount(): LiveData<Int>

    /** 今日拦截次数（今天0点之后） */
    @Query("SELECT COUNT(*) FROM interception_records WHERE timestamp >= :todayStart")
    fun getTodayCount(todayStart: Long): LiveData<Int>

    /** 按APP聚合统计 */
    @Query(
        """
        SELECT packageName,
               COUNT(*) AS totalCount,
               SUM(CASE WHEN type = 1 THEN 1 ELSE 0 END) AS splashCount,
               SUM(CASE WHEN type = 2 THEN 1 ELSE 0 END) AS jumpCount
        FROM interception_records
        GROUP BY packageName
        ORDER BY totalCount DESC
        """
    )
    fun getStatsByApp(): LiveData<List<AppStats>>

    /** 清空所有记录 */
    @Query("DELETE FROM interception_records")
    suspend fun clearAll()
}
