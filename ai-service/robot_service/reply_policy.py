"""对话的生成预算与朗读上限；短句不等于只保留前两句。"""

import re

# Android 按句预取朗读，并最多接收 600 字；Speak 也使用此上限。
MAX_REPLY_CHARS = 600
STORY_PREFIX = "这是我编的小故事。"


def turn_guidance(question, *, story=False):
    """把本轮详略要求放在模板末尾，避免小模型被其他场景的规则干扰。"""
    if re.search(
        r"一句话|一句就|简短|简单说|短一点|少说点|one sentence|briefly", question, re.I
    ):
        return "本轮明确要求简短，优先遵从：只用一句话直接回答，不加例子、比喻或扩展。故事仍需在简短篇幅内有结局。"
    if story:
        return "本轮任务是讲完整故事，请讲350—500字，具体写出角色的行动、尝试和解决过程，必须在本次回答给出结局，不要只写梗概。"
    if re.search(r"英语|英文|\b(?:english|translate)\b", question, re.I):
        return "本轮按英语学习请求回答，用准确表达、中文含义和一个生活例句帮助理解；只翻译或问候可以更短，不扩展无关知识。"
    return "本轮根据孩子的请求回答：知识问题先给结论，再讲清原因和具体过程，必要时补真实例子；寒暄、确认和简单事实简短即可。不要为有趣而编造解释。"


def generation_options(*, story=False, visual=False):
    return {
        "num_ctx": 4096 if visual else 8192,
        "num_predict": 256 if visual else 1024,
        "temperature": 0.4 if story else 0,
    }


def bound_reply(text, limit=MAX_REPLY_CHARS):
    """异常超长输出优先在完整句末收住，避免切掉半句话。"""
    text = text.strip()
    if len(text) <= limit:
        return text
    endings = list(re.finditer(r"[。！？.!?][”’\"]?", text[:limit]))
    if endings:
        return text[: endings[-1].end()]
    return text[: limit - 1].rstrip("，,、；;：: ") + "。"
