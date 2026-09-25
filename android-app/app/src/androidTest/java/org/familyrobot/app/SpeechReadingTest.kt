package org.familyrobot.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeechReadingTest {
    private fun plan(mode:String="continuous")=JSONObject().put("readingMode",mode).put("segments",JSONArray().apply {
        put(JSONObject().put("id","a").put("pageId","p1").put("groupId","a"))
        put(JSONObject().put("id","b").put("pageId","p2").put("groupId","a"))
        put(JSONObject().put("id","c").put("pageId","p3").put("groupId","c"))
    })
    @Test fun continuousAuditionIncludesAdjacentPagesInBookOrder(){
        assertEquals(listOf("a","b"),bookPreviewSegments(plan(),"p2",true).map{it.getString("id")})
        assertEquals(listOf("b"),bookPreviewSegments(plan(),"p2",false).map{it.getString("id")})
        assertEquals(listOf("b"),bookPreviewSegments(plan("follow_pages"),"p2",true).map{it.getString("id")})
        assertTrue(bookPreviewSegments(plan(),"missing",true).isEmpty())
    }
    @Test fun longPageAuditionRetainsEverySegment(){
        val p=plan();p.getJSONArray("segments").getJSONObject(1).put("pageId","p1").put("groupId","b")
        assertEquals(listOf("a","b"),bookPreviewSegments(p,"p1",true).map{it.getString("id")})
    }
    @Test fun readingAndVoiceChangesInvalidateAudition(){
        val draft=JSONObject().put("voiceSource","shared").put("readingMode","continuous")
            .put("voice",JSONObject().put("zh","Serena")).put("pages",JSONArray().put(JSONObject().put("synthesisVariant",0)))
        val original=bookAuditionKey(draft)
        draft.put("auditioned",true);assertEquals(original,bookAuditionKey(draft))
        for(key in listOf("readingMode","voiceSource")){
            val changed=JSONObject(draft.toString()).put(key,"changed")
            assertNotEquals(original,bookAuditionKey(changed))
        }
        draft.getJSONArray("pages").getJSONObject(0).put("synthesisVariant",1)
        assertNotEquals(original,bookAuditionKey(draft))
    }
}
