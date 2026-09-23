package org.familyrobot.core

/** 仅本地保留最近两秒，唤醒后供首句一次性补齐；普通回答结束不能重放机器人声音。 */
class WakeAudioBuffer(private val capacity:Int=32000) {
    private val samples=FloatArray(capacity)
    private var cursor=0
    private var size=0
    private var armed=false
    fun offer(input:FloatArray) { for(value in input) { samples[cursor]=value;cursor=(cursor+1)%capacity;if(size<capacity)size++ } }
    fun arm() { armed=true }
    fun clear() { armed=false;size=0;cursor=0;samples.fill(0f) }
    fun takeForFirstUtterance():FloatArray? {
        if(!armed)return null
        val output=FloatArray(size) { samples[(cursor-size+capacity+it)%capacity] }
        clear();return output
    }
}
