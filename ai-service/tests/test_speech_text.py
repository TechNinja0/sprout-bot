import pytest
from robot_service.speech_text import normalize_text


@pytest.mark.parametrize(
    ("text", "spoken"),
    [
        (
            "有0、10、11、20、101、1001个。",
            "有零、十、十一、二十、一百零一、一千零一个。",
        ),
        (
            "10000、10001、10010、100000、10000001",
            "一万、一万零一、一万零一十、十万、一千万零一",
        ),
        ("共12.05元，增长3.5%。", "共十二点零五元，增长百分之三点五。"),
        ("温度-3.2℃，+5°C。", "温度负三点二摄氏度，正五摄氏度。"),
        (
            "重2.5kg，长30cm，水250 mL，跑10km/h。",
            "重二点五千克，长三十厘米，水二百五十毫升，跑十千米每小时。",
        ),
        (
            "速度1m/s，长度2km、3mm，质量4g、5mg，水6L。",
            "速度一米每秒，长度二千米、三毫米，质量四克、五毫克，水六升。",
        ),
        ("AI与NASA，USB-C和GPT-4，5G网络。", "AI与NASA，USB-C和GPT-4，5G网络。"),
        ("电话13800138000，编号007。", "电话13800138000，编号007。"),
        (
            "2026-09-25，2026年9月25日，9月25日，12:30，1/2，v1.2.3。",
            "2026-09-25，2026年9月25日，9月25日，12:30，1/2，v1.2.3。",
        ),
        (
            "https://test.com/12?q=3.5%，1,000，3e-2，0.1234567",
            "https://test.com/12?q=3.5%，1,000，3e-2，0.1234567",
        ),
        ("123456789，25GB，３.５％", "123456789，25GB，３.５％"),
    ],
)
def test_conservative_chinese_normalization(text, spoken):
    assert normalize_text(text, "zh") == spoken
    assert normalize_text(spoken, "zh") == spoken


def test_english_is_unchanged_and_input_not_mutated():
    text = "NASA has 12.5 kg, up 5%."
    assert normalize_text(text, "en") == text
    assert text == "NASA has 12.5 kg, up 5%."
    with pytest.raises(ValueError):
        normalize_text(text, "auto")
