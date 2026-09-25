"""对话的生成预算与朗读上限；短句不等于只保留前两句。"""

import re

# Android 按句预取朗读，并最多接收 600 字；Speak 也使用此上限。
MAX_REPLY_CHARS = 600
STORY_PREFIX = "这是我编的小故事。"
ANSWER_GUIDANCE = (
    "回答原则：知识库没有收录不等于问题不能回答。普通百科问题直接讲解已知的可靠知识，"
    "不要用‘问爸爸妈妈’代替解释。只是不确定某个细节时，先讲清能确定的部分，"
    "再具体说明哪个细节不知道，不因局部未知放弃整个回答，也不把猜测当事实。"
    "问题含有错误前提时先温和纠正。没有公认精确答案时解释为什么无法精确确定。"
    "本轮没有联网检索结果，不声称刚查过资料，不编造来源、链接、实时数据或家庭私事。"
    "家庭物品位置、个人安排只依据已提供的上下文，不知道时说明需要哪条信息。"
    "涉及用药、入口安全、火电等实际操作时不做安全保证，解释原因并请成人协助；"
    "一般科学原理仍可解释，不提供危险操作步骤。"
)


def turn_guidance(question, *, story=False):
    """把本轮详略要求放在模板末尾，避免小模型被其他场景的规则干扰。"""
    if re.search(
        r"一句话|一句就|简短|简单说|短一点|少说点|one sentence|briefly", question, re.I
    ):
        return (
            ANSWER_GUIDANCE
            + "\n本轮明确要求简短，优先遵从：只用一句话直接回答，不加例子、比喻或扩展。故事仍需在简短篇幅内有结局。"
        )
    if story:
        return (
            ANSWER_GUIDANCE
            + "\n本轮任务是讲完整故事，请讲350—500字，具体写出角色的行动、尝试和解决过程，必须在本次回答给出结局，不要只写梗概。"
        )
    if re.search(r"英语|英文|\b(?:english|translate)\b", question, re.I):
        return (
            ANSWER_GUIDANCE
            + "\n本轮按英语学习请求回答，用准确表达、中文含义和一个生活例句帮助理解；只翻译或问候可以更短，不扩展无关知识。"
        )
    return (
        ANSWER_GUIDANCE
        + "\n本轮根据孩子的请求回答：知识问题先给结论，再讲清原因和具体过程，必要时补真实例子；寒暄、确认和简单事实简短即可。不要为有趣而编造解释。"
    )


def safety_reply(text):
    """保留危险操作的确定性拦截，同时解释原因，不只把问题推给家长。"""
    if "药" in text:
        return (
            "药物可以用来预防或治疗疾病，但不同的药用途不同，使用方法和用量也不同。"
            "药不是糖果，不能看着像、闻着香就自己尝。"
            "如果你问的是自己能不能吃某种药，要请爸爸妈妈核对医生或药师的说明，我不能替你决定。"
        )
    if "插座" in text:
        return (
            "插座把家里的电连接到电器上，里面的金属部件可能带电。"
            "人体也能导电，接触带电部件可能受伤，所以不能把手指或物品伸进插孔。"
            "需要使用或检查插座时，请爸爸妈妈来处理。"
        )
    if "煤气" in text or "点火" in text:
        return (
            "燃烧会释放热量，但火焰可能造成烫伤或引燃旁边的物品，燃气泄漏也很危险。"
            "不要自己点火或操作燃气设备。请爸爸妈妈来处理，我可以讲原理，但不能教你独自尝试。"
        )
    if any(word in text for word in ("能吃", "可以吃", "能喝", "可以喝", "有毒")):
        return (
            "有些东西外表很像，成分却不同；有毒、变质或不适合入口的东西可能让人生病。"
            "只凭一句描述或一张照片，我不能确认眼前的东西能不能吃喝。"
            "不认识或不确定的东西先不要尝，请爸爸妈妈确认。"
        )
    return None


def unavailable_fact_reply(text):
    """当前未接入实时数据；已知缺失的数据不能交给小模型自行猜测。"""
    if re.search(r"英语|英文|翻译|\btranslate\b", text, re.I) or re.search(
        r"什么是(?:天气|气温|空气质量)|(?:天气|气温|空气质量|预报)(?:是什么|是什么意思)|"
        r"(?:为什么|怎么|如何).{0,8}(?:预测|预报)|(?:预报|预测).{0,8}(?:为什么|怎么|如何|原理)",
        text,
    ):
        return None
    if re.search(r"天气|气温|下雨|下雪|晴天|阴天|空气质量|雾霾", text) and re.search(
        r"今天|明天|后天|今晚|现在|当前|实时|这周|本周|周末|预报", text
    ):
        return (
            "我现在没有接入实时天气，不能确认你问的天气、温度或空气质量。"
            "这些会随地点和时间变化，可以查看当地天气预报中的温度、降雨概率和预警，帮助安排出门。"
        )
    return None


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
