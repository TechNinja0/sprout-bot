package org.familyrobot.core

import org.junit.Assert.*
import org.junit.Test

class VisualIntentTest {
    @Test fun explicitCurrentObjectRequestsTakeFreshFrames() {
        for(text in listOf("这是什么？","你看我拿的什么东西","这个怎么用","这个有什么用","这是什么颜色","我手里有几个","看看现在","这个","这个呢","我换一个"))
            assertTrue(text,VisualIntent.needsFreshFrame(text))
    }
    @Test fun englishObjectRequestsUseTheSameFreshFrameRule() {
        for(text in listOf("What is this?","What's that?","What color is this?","How do I use this?","Look at this."))
            assertTrue(text,VisualIntent.needsFreshFrame(text))
    }
    @Test fun controlsAndAbstractReferencesNeverUploadAFrame() {
        for(text in listOf("不要记这个","别看了","不要拍了","关闭相机","这个故事真好听","这个词怎么用","我不喜欢这个声音","这本书为什么有很多页","你看过这本书吗","换一个","继续","你喜欢什么颜色","What is this sound?","Look at this story.","Don't look at this."))
            assertFalse(text,VisualIntent.needsFreshFrame(text))
    }
    @Test fun priorObjectPronounsReuseConversationWithoutRecapture() {
        for(text in listOf("它怎么用","刚才那个有什么用","它是什么颜色","How do I use it?"))
            assertFalse(text,VisualIntent.needsFreshFrame(text))
    }
}
