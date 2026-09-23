package org.familyrobot.core

/** 普通发布保留阅读快照；下架/删除及明确撤销的版本才停止。 */
object ResourceVersions {
    fun withdrawn(revision:String,activeRevision:String,revokedRevisionIds:Set<String>?):Boolean {
        if(revision.isEmpty())return false
        if(activeRevision.isEmpty())return true
        // 老服务没有精确列表时沿用保守撤回语义，不能放行可能已撤销的内容。
        return revokedRevisionIds?.contains(revision) ?: (revision!=activeRevision)
    }
}
