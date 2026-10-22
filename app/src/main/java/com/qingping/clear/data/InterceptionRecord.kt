package com.qingping.clear.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一条拦截记录
 * packageName: 被拦截广告所属的APP包名
 * timestamp:   拦截时间戳
 * type:        1=开屏广告  2=误触跳转
 */
@Entity(tableName = "interception_records")
data class InterceptionRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val type: Int = 1
)
