"""孩子主动开启的短游戏；最多三题、两分钟，不保存成绩或判断能力。"""

import re
import time


def respond(games, key, text, age, start=False):
    now = time.monotonic()
    expired = key in games and now - games[key]["started"] >= 120
    for item in list(games):
        if now - games[item]["started"] >= 120:
            games.pop(item)
    if start:
        if len(games) >= 100:
            games.pop(next(iter(games)))
        if "猜谜" in text:
            rounds = [
                ("第一题：小耳朵，喵喵叫，是谁？", ("猫", "cat"), "是小猫，cat。"),
                (
                    "第二题：长耳朵，蹦蹦跳，是谁？",
                    ("兔", "rabbit", "bunny"),
                    "是小兔，rabbit。",
                ),
                ("第三题：汪汪叫，摇尾巴，是谁？", ("狗", "dog"), "是小狗，dog。"),
            ]
        elif "数数" in text:
            rounds = [
                ("One, two，接下来是几？", ("3", "三", "three"), "Three，三。"),
                ("Two, three，接下来是几？", ("4", "四", "four"), "Four，四。"),
                ("Three, four，接下来是几？", ("5", "五", "five"), "Five，五。"),
            ]
            if age >= 8:
                rounds[-1] = ("Four 加 one，是几？", ("5", "五", "five"), "Five，五。")
        else:
            rounds = [
                ("红色用英语怎么说？可以跟我说 red。", ("红", "red"), "Red，红色。"),
                ("蓝色可以说 blue。我们轻轻说一遍。", ("蓝", "blue"), "Blue，蓝色。"),
                (
                    "黄色可以说 yellow。我们轻轻说一遍。",
                    ("黄", "yellow"),
                    "Yellow，黄色。",
                ),
            ]
        games[key] = {"started": now, "round": 0, "rounds": rounds}
        return "我们玩三小题，随时可以说停止。" + rounds[0][0]
    if expired:
        return "小游戏先到这里，我们休息一下。"
    game = games.get(key)
    if not game:
        return None
    if re.search(r"不玩|结束游戏|换个话题|聊天|[？?]|为什么|怎么|什么", text):
        games.pop(key)
        return None
    _, answers, explanation = game["rounds"][game["round"]]
    # 不给成绩、不连续要求纠错；听错或未答对也只示范一次并进入下一小题。
    matched = any(answer in text.casefold() for answer in answers)
    feedback = ("对啦，" if matched else "我们一起听：") + explanation
    game["round"] += 1
    if game["round"] == len(game["rounds"]):
        games.pop(key)
        return feedback + "三小题结束啦。"
    return feedback + game["rounds"][game["round"]][0]
