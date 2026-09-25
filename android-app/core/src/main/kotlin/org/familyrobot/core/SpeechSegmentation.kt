package org.familyrobot.core

/** 首个完整句尽早播放，后续合并；保留原文，只有超长单句才按上限兜底切分。 */
object SpeechSegmentation {
    private const val maxLength=600
    private const val targetLength=160
    private val endings="。！？.!?…"
    private val pairs=mapOf('“' to '”','‘' to '’','「' to '」','『' to '』','(' to ')','（' to '）','[' to ']','【' to '】')
    private val abbreviations=setOf("mr","mrs","ms","dr","prof","sr","jr","st","vs","etc","e.g","i.e","a.m","p.m","u.s","u.k","ph.d","no","fig","vol")
    private val word=Regex("[A-Za-z][A-Za-z.]*$")
    private val initials=Regex("(?:[A-Za-z]\\.)+[A-Za-z]")

    /** 沿用中文优先的选择，但由整次回答决定，不能随分段切换。 */
    fun language(text:String)=if(text.any { it in '\u3400'..'\u9fff' })"zh" else "en"

    fun split(text:String):List<String> {
        if(text.isEmpty())return emptyList()
        val sentences=mutableListOf<String>()
        val quotes=mutableListOf<Char>()
        var start=0;var i=0
        while(i<text.length) {
            val c=text[i]
            if(c in endings && (c!='.' || sentencePeriod(text,i))) {
                var end=i+1
                val closing=quotes.toMutableList()
                while(end<text.length) {
                    val next=text[end]
                    when {
                        next in endings -> end++
                        closing.lastOrNull()==next -> { closing.removeAt(closing.lastIndex);end++ }
                        else -> break
                    }
                }
                val following=text.drop(end).firstOrNull { !it.isWhitespace() }
                // 引号中的问句后接逗号或英文叙述语时，仍属于同一句。
                val continuation=quotes.isNotEmpty() && following!=null && (following in ",，:：;；" || following in 'a'..'z')
                if(closing.isEmpty() && !continuation) {
                    while(end<text.length && text[end].isWhitespace())end++
                    sentences.add(text.substring(start,end));start=end;i=end;quotes.clear()
                    continue
                }
            }
            when {
                quotes.lastOrNull()==c && !apostrophe(text,i) -> quotes.removeAt(quotes.lastIndex)
                c in pairs -> quotes.add(pairs.getValue(c))
                (c=='"' || c=='\'') && !apostrophe(text,i) && (i==0 || !text[i-1].isLetterOrDigit()) -> quotes.add(c)
            }
            i++
        }
        if(start<text.length)sentences.add(text.substring(start))
        val chunks=mutableListOf<String>()
        var pending=""
        for(sentence in sentences) {
            var offset=0
            while(offset<sentence.length) {
                var end=minOf(offset+maxLength,sentence.length)
                if(end<sentence.length && sentence[end-1].isHighSurrogate() && sentence[end].isLowSurrogate())end--
                val part=sentence.substring(offset,end)
                if(pending.length+part.length>maxLength) { chunks.add(pending);pending="" }
                pending+=part
                if(chunks.isEmpty() || pending.length>=targetLength) { chunks.add(pending);pending="" }
                offset=end
            }
        }
        if(pending.isNotEmpty())chunks.add(pending)
        return chunks
    }

    private fun apostrophe(text:String,index:Int)=text[index] in "'’" && index>0 && index+1<text.length &&
        text[index-1].isLetterOrDigit() && text[index+1].isLetterOrDigit()

    private fun sentencePeriod(text:String,index:Int):Boolean {
        if(index>0 && index+1<text.length && text[index-1].isLetterOrDigit() && text[index+1].isLetterOrDigit())return false
        val token=word.find(text.substring(0,index))?.value ?: return true
        val following=text.drop(index+1).firstOrNull { !it.isWhitespace() } ?: return true
        if(token.lowercase() in abbreviations || initials.matches(token))return false
        return !(token.length==1 && token[0] in 'A'..'Z' && following in 'A'..'Z')
    }
}
