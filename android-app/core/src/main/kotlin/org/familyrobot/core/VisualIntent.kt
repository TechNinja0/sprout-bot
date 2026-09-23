package org.familyrobot.core

/** 只为明确指向眼前画面的请求取帧；普通指代、记忆及停止口令不发送画面。 */
object VisualIntent {
    fun needsFreshFrame(text:String):Boolean {
        val value=text.lowercase().replace(Regex("[，。！？,.!?]"),"").trim()
        val compact=value.replace(Regex("\\s+"),"")
        if(listOf("别看", "不要看", "不要拍", "关闭相机", "不要记", "别记").any { compact.contains(it) })return false
        if(Regex("\\b(?:don't|do not|stop) (?:look|looking|record|recording|watch|watching)\\b|\\b(?:this|that) (?:story|sound|word|setting|memory|question|answer|song|name)\\b").containsMatchIn(value))return false
        if(Regex("(?:这|那)(?:个|首|段|句|种|本)?(?:故事|声音|设置|记忆|问题|答案|说法|意思|道理|词|英文|中文|名字|计划|儿歌|歌曲)").containsMatchIn(compact))return false
        if(compact in setOf("这个","那个","这个呢","那个呢","我换一个","看看现在"))return true
        if(Regex("看一下|看一看|看看|看我拿|看我手|看这里|看那边").containsMatchIn(compact))return true
        if(compact.startsWith("刚才那个") || compact.startsWith("刚刚那个"))return false
        val pointsToObject=Regex("这|那|我拿|手里|手上|桌上|面前|眼前").containsMatchIn(compact)
        val asksAboutObject=Regex("是什么|是啥|什么东西|什么颜色|什么形状|怎么用|有什么用|做什么用|干什么用|几个|几只|几种").containsMatchIn(compact)
        if(pointsToObject && asksAboutObject)return true
        return Regex("\\b(?:what(?:'s| is) (?:this|that)|what (?:colou?r|shape) is (?:this|that)|how (?:do i|to) use (?:this|that)|look (?:at )?(?:this|that|here)|look now)\\b").containsMatchIn(value)
    }
}
