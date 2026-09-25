"""只处理送入 Qwen 的副本，不改动展示/存储文本。

中文支持不超过八位的整数（无前导零）、至多六位小数、百分比及下列单位。
不推断字母缩写、编号、电话、日期、时间、分数、版本、URL 或科学计数法；
英文保持原样。规则调整须升级 TEXT_PROFILE。
"""

import re

TEXT_PROFILE = "zh-number-unit-v1"
_DIGITS = "零一二三四五六七八九"
_UNITS = {
    "km/h": "千米每小时",
    "m/s": "米每秒",
    "kg": "千克",
    "km": "千米",
    "cm": "厘米",
    "mm": "毫米",
    "mg": "毫克",
    "ml": "毫升",
    "mL": "毫升",
    "°C": "摄氏度",
    "℃": "摄氏度",
    "°F": "华氏度",
    "m": "米",
    "g": "克",
    "L": "升",
    "l": "升",
}
_NUMBER = re.compile(
    r"(?<![A-Za-z0-9_./:+%％−-])([+−-]?[0-9]+(?:\.[0-9]+)?)"
    r"(?:[ \t]*(%|％|" + "|".join(map(re.escape, _UNITS)) + r"))?"
    r"(?![A-Za-z0-9_./:%％−-])"
)
_PROTECTED = re.compile(
    r"https?://\S+|www\.\S+|[0-9]+(?:[,，][0-9]{3})+"
    r"|[0-9]{2,4}年(?:[0-9]{1,2}月)?(?:[0-9]{1,2}日)?"
    r"|[0-9]{1,2}月[0-9]{1,2}日"
)


def _section(value):
    result = ""
    zero = False
    for divisor, unit in ((1000, "千"), (100, "百"), (10, "十"), (1, "")):
        digit, value = divmod(value, divisor)
        if digit:
            if zero:
                result += "零"
            result += _DIGITS[digit] + unit
            zero = False
        elif result and value:
            zero = True
    return result


def _integer(value):
    if value == 0:
        return "零"
    high, low = divmod(value, 10000)
    result = _section(high) + "万" if high else ""
    if high and 0 < low < 1000:
        result += "零"
    result += _section(low)
    return result[1:] if result.startswith("一十") else result


def _spoken_number(raw):
    sign = "负" if raw.startswith(("-", "−")) else "正" if raw.startswith("+") else ""
    raw = raw.lstrip("+−-")
    integer, dot, fraction = raw.partition(".")
    if (
        len(integer) > 8
        or len(fraction) > 6
        or (len(integer) > 1 and integer[0] == "0")
    ):
        return None
    spoken = sign + _integer(int(integer))
    if dot:
        spoken += "点" + "".join(_DIGITS[int(digit)] for digit in fraction)
    return spoken


def normalize_text(text: str, language: str = "zh") -> str:
    """返回保守的朗读副本；既有中文数字和英文缩写不重写。"""
    if language not in ("zh", "en"):
        raise ValueError("语言仅支持 zh/en")
    if language == "en":
        return text
    protected = [m.span() for m in _PROTECTED.finditer(text)]

    def replace(match):
        if any(match.start() < end and match.end() > start for start, end in protected):
            return match.group()
        spoken = _spoken_number(match[1])
        if spoken is None:
            return match.group()
        unit = match[2]
        if unit in ("%", "％"):
            return "百分之" + spoken
        return spoken + _UNITS.get(unit, "")

    return _NUMBER.sub(replace, text)
