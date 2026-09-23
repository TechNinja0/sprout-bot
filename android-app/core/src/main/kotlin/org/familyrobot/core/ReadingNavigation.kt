package org.familyrobot.core

/** 只解释明确的阅读控制，普通提问仍交给对话；定位完全基于已发布页序与标签。 */
object ReadingNavigation {
    sealed interface Command {
        data object Restart:Command
        data object Resume:Command
        data class Page(val label:String,val chapter:String?=null):Command
        data class Chapter(val label:String):Command
    }
    data class Segment(val pageId:String,val label:String,val chapter:String)
    sealed interface Location {
        data class Found(val index:Int,val notice:String=""):Location
        data class Unavailable(val message:String):Location
    }
    private fun clean(text:String)=text.trim().replace(Regex("[\\s，。！？,.!?]"),"").lowercase()
    private val number="[0-9零〇一二两三四五六七八九十百]+"
    fun command(text:String):Command? {
        val value=clean(text).removePrefix("请")
        if(value in setOf("从头读","从头开始","从头开始读","重新读","重新开始读","startover","readfromthebeginning"))return Command.Restart
        if(value in setOf("继续","继续读","接着读","继续上次","接着上次读","resume","continue"))return Command.Resume
        val page=Regex("(?:读|从|跳到|翻到|读到)?(?:第?($number)章)?第?($number)页(?:开始读|开始|继续读)?").matchEntire(value)
        if(page!=null)return Command.Page(page.groupValues[2],page.groupValues[1].ifBlank { null })
        val chapter=Regex("(?:读|从|跳到|翻到|读到)?第?($number)章(?:开始读|开始)?").matchEntire(value)
        if(chapter!=null)return Command.Chapter(chapter.groupValues[1])
        return null
    }
    fun choice(text:String):Int? = when(clean(text).removePrefix("读").removeSuffix("本")) {
        "第一个","第一","第1个","第1","一","1","第一个版本","first","firstone" -> 0
        "第二个","第二","第2个","第2","二","2","第二个版本","second","secondone" -> 1
        else -> null
    }
    fun cancelled(text:String)=clean(text) in setOf("取消","不读了","算了","cancel","nevermind")
    fun number(text:String):Int? {
        text.toIntOrNull()?.let { return it }
        val digits=mapOf('零' to 0,'〇' to 0,'一' to 1,'二' to 2,'两' to 2,'三' to 3,'四' to 4,'五' to 5,'六' to 6,'七' to 7,'八' to 8,'九' to 9)
        var sum=0;var digit=0
        for(c in text)when {
            c in digits -> digit=digits.getValue(c)
            c=='十' -> { sum+=(if(digit==0)1 else digit)*10;digit=0 }
            c=='百' -> { sum+=(if(digit==0)1 else digit)*100;digit=0 }
            else -> return null
        }
        return (sum+digit).takeIf { text.isNotEmpty() }
    }
    private fun labelNumber(text:String,unit:String):Int? {
        val value=clean(text).removePrefix("第").removeSuffix(unit)
        return number(value)
    }
    private fun chapterMatches(label:String,wanted:String):Boolean {
        val n=number(wanted) ?: return clean(label)==clean(wanted)
        return labelNumber(label,"章")==n || Regex("第?($number)章.*").matchEntire(clean(label))?.groupValues?.get(1)?.let { number(it)==n }==true
    }
    fun locate(command:Command,segments:List<Segment>):Location {
        if(segments.isEmpty())return Location.Unavailable("这本书没有可定位的文字页。")
        if(command==Command.Restart)return Location.Found(0)
        if(command is Command.Chapter) {
            val indices=segments.indices.filter { chapterMatches(segments[it].chapter,command.label) }
            if(indices.isEmpty())return Location.Unavailable("还没有录入这个章节，请爸爸妈妈核对章节名称。")
            if(indices.map { segments[it].chapter }.distinct().size>1)return Location.Unavailable("有多个同号章节，请爸爸妈妈先补全章节名称。")
            return Location.Found(indices.first())
        }
        if(command !is Command.Page)return Location.Unavailable("请说要读的页码。")
        val pages=segments.indices.distinctBy { segments[it].pageId }
        val scoped=pages.filter { command.chapter==null || chapterMatches(segments[it].chapter,command.chapter) }
        val requested=number(command.label) ?: return Location.Unavailable("没有识别到页码，请再说一次。")
        if(pages.all { segments[it].label.isBlank() } && command.chapter==null) {
            return scoped.getOrNull(requested-1)?.let { Location.Found(it,"这本书没有标注原书页码，我按录入顺序读第${requested}页。") }
                ?: Location.Unavailable("录入的内容没有这么多页。")
        }
        val matches=scoped.filter { labelNumber(segments[it].label,"页")==requested }
        if(matches.size>1)return Location.Unavailable("有多个第${requested}页，请同时告诉我第几章；没有章节标注时，请爸爸妈妈先补全。")
        return matches.singleOrNull()?.let { Location.Found(it) } ?: Location.Unavailable("已录入的内容里没有这个页码，请爸爸妈妈核对原书页码。")
    }
}
