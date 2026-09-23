"""Editable templates live in configuration; protocol and permission rules stay in code."""

import json
import re
from pathlib import Path

from pydantic import Field, field_validator

from .schemas_base import Strict


def defaults():
    return json.loads((Path(__file__).parent / "data/default-prompts.json").read_text())


class Prompts(Strict):
    daily: str = Field(
        default_factory=lambda: defaults()["daily"], min_length=1, max_length=4000
    )
    english: str = Field(
        default_factory=lambda: defaults()["english"], min_length=1, max_length=4000
    )
    story: str = Field(
        default_factory=lambda: defaults()["story"], min_length=1, max_length=4000
    )
    visual: str = Field(
        default_factory=lambda: defaults()["visual"], min_length=1, max_length=4000
    )

    @field_validator("*")
    @classmethod
    def template(cls, value):
        if not value.strip():
            raise ValueError("提示词不能为空")
        for key in re.findall(r"\{\{(.*?)\}\}", value):
            if key not in ("robot_name", "age", "english_level"):
                raise ValueError("不支持的提示词变量")
        stripped = re.sub(r"\{\{(?:robot_name|age|english_level)\}\}", "", value)
        if "{{" in stripped or "}}" in stripped:
            raise ValueError("提示词变量括号不完整")
        return value


def expand(template, config, age):
    variables = {
        "robot_name": config["nickname"],
        "age": str(age),
        "english_level": config["profile"]["englishLevel"],
    }
    return re.sub(
        r"\{\{(robot_name|age|english_level)\}\}", lambda m: variables[m[1]], template
    )
