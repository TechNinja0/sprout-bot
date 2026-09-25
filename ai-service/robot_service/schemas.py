from datetime import date
from typing import Literal
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from pydantic import Field, field_validator, model_validator

from .prompt_config import Prompts
from .schemas_base import Strict


class Interval(Strict):
    days: list[int] = Field(
        default_factory=lambda: list(range(1, 8)), min_length=1, max_length=7
    )
    start: str = Field(default="20:00", pattern=r"^(?:[01]\d|2[0-3]):[0-5]\d$")
    end: str = Field(default="09:00", pattern=r"^(?:[01]\d|2[0-3]):[0-5]\d$")

    @field_validator("days")
    @classmethod
    def valid_days(cls, value):
        if any(d < 1 or d > 7 for d in value):
            raise ValueError("星期须为1—7")
        return sorted(set(value))


class Policy(Strict):
    timezone: str = "Asia/Shanghai"
    intervals: list[Interval] = Field(
        default_factory=lambda: [Interval()], max_length=14
    )
    dailyMinutes: int = Field(default=0, ge=0, le=240)
    mediaMinutes: int = Field(default=30, ge=1, le=120)
    manualBlocked: bool = False
    overrideUntil: float = Field(default=0, ge=0)

    @field_validator("timezone")
    @classmethod
    def valid_zone(cls, value):
        try:
            ZoneInfo(value)
        except ZoneInfoNotFoundError:
            raise ValueError("未知时区")
        return value


class Profile(Strict):
    ageAtBaseline: int = Field(default=5, ge=3, le=17)
    baseline: date = date(2026, 9, 22)
    birthMonth: str = Field(default="", pattern=r"^(?:\d{4}-(?:0[1-9]|1[0-2]))?$")
    englishLevel: Literal["beginner", "basic", "intermediate"] = "beginner"
    expression: Literal["simple", "adaptive"] = "adaptive"


class Voice(Strict):
    quality: Literal["standard", "high"] = "high"
    zh: str = Field(default="default", max_length=80)
    en: str = Field(default="default", max_length=80)
    story: str = Field(default="default", max_length=80)
    speed: float = Field(default=1.0, ge=0.7, le=1.3)
    volume: float = Field(default=0.5, ge=0, le=1)
    style: Literal["neutral", "gentle", "cheerful", "storytelling", "soothing"] = (
        "neutral"
    )
    storyStyle: Literal[
        "default", "neutral", "gentle", "cheerful", "storytelling", "soothing"
    ] = "default"
    instruction: str = Field(default="", max_length=200)


class ListeningPlan(Strict):
    id: str = Field(pattern=r"^[a-f0-9]{32}$")
    playlistId: str = Field(pattern=r"^[a-f0-9]{32}$")
    enabled: bool = False
    days: list[int] = Field(
        default_factory=lambda: list(range(1, 8)), min_length=1, max_length=7
    )
    time: str = Field(default="18:30", pattern=r"^(?:[01]\d|2[0-3]):[0-5]\d$")
    minutes: int = Field(default=10, ge=1, le=30)

    @field_validator("days")
    @classmethod
    def valid_days(cls, value):
        if any(d < 1 or d > 7 for d in value):
            raise ValueError("星期须为1—7")
        return sorted(set(value))


class Interaction(Strict):
    wakeFeedback: Literal["voice", "chime", "visual"] = "voice"
    playful: bool = True
    wakeSensitivity: Literal["low", "standard", "high"] = "standard"
    expressionIntensity: Literal["gentle", "normal"] = "gentle"


class HistoryPolicy(Strict):
    enabled: bool = False
    days: Literal[7, 30, 90] = 7


class Config(Strict):
    prompts: Prompts = Field(default_factory=Prompts)
    history: HistoryPolicy = Field(default_factory=HistoryPolicy)
    nickname: str = Field(default="小伙伴", min_length=1, max_length=20)

    @field_validator("nickname")
    @classmethod
    def valid_nickname(cls, value):
        from .keywords import generate

        generate(value)
        return value

    cameraAllowed: bool = True
    muted: bool = False
    reducedMotion: bool = False
    interaction: Interaction = Field(default_factory=Interaction)
    policy: Policy = Field(default_factory=Policy)
    profile: Profile = Field(default_factory=Profile)
    voice: Voice = Field(default_factory=Voice)
    originalStories: bool = False
    proactive: bool = False
    listeningPlans: list[ListeningPlan] = Field(default_factory=list, max_length=10)
    # 首版没有外部模型适配器，不能仅修改布尔值隐含启用出站。
    cloudText: Literal[False] = False
    cloudAudio: Literal[False] = False
    cloudImage: Literal[False] = False


class Register(Strict):
    invite: str = Field(min_length=20, max_length=200)
    name: str = Field(min_length=1, max_length=64)


class PairClaim(Register):
    pass


class PairDecision(Strict):
    approved: bool


class ClaimPoll(Strict):
    claim: str = Field(min_length=20, max_length=200)
    token: str = Field(min_length=32, max_length=200)


class ConfigRequest(Strict):
    requestId: str = Field(min_length=16, max_length=80)
    expectedVersion: int = Field(ge=0)
    config: Config


class FailureCounts(Strict):
    turn: int = Field(default=0, ge=0, le=1_000_000_000)
    media: int = Field(default=0, ge=0, le=1_000_000_000)
    download: int = Field(default=0, ge=0, le=1_000_000_000)
    network: int = Field(default=0, ge=0, le=1_000_000_000)
    progress_overflow: int = Field(
        default=0, alias="progress-overflow", ge=0, le=1_000_000_000
    )
    progress_rejected: int = Field(
        default=0, alias="progress-rejected", ge=0, le=1_000_000_000
    )
    progress_response: int = Field(
        default=0, alias="progress-response", ge=0, le=1_000_000_000
    )


class PlaybackStatus(Strict):
    state: Literal["idle", "playing", "paused", "loading"] = "idle"
    resourceId: str = Field(default="", max_length=64)
    revisionId: str = Field(default="", max_length=64)
    title: str = Field(default="", max_length=200)
    positionMs: int = Field(default=0, ge=0)
    segment: int = Field(default=0, ge=0)
    total: int = Field(default=0, ge=0)


class Heartbeat(Strict):
    playback: PlaybackStatus = Field(default_factory=PlaybackStatus)
    wakeName: str = Field(default="", max_length=20)
    wakeVersion: int = Field(default=0, ge=0)
    status: str = Field(default="standby", max_length=40)
    appliedVersion: int = Field(default=0, ge=0)
    camera: bool = False
    microphone: bool = False
    reason: str = Field(default="", max_length=80)
    serviceFailures: FailureCounts = Field(default_factory=FailureCounts)


class Ack(Strict):
    applied: bool
    version: int = Field(ge=0)


class Page(Strict):
    id: str = Field(min_length=1, max_length=64)
    label: str = Field(default="", max_length=30)
    chapter: str = Field(default="", max_length=100)
    sourceAsset: str = Field(default="", max_length=64)
    sourcePage: int = Field(default=0, ge=0, le=299)
    sourceRotation: Literal[0, 90, 180, 270] = 0
    extractionError: str = Field(default="", max_length=80)
    confidence: float | None = Field(default=None, ge=0, le=1)
    qualityWarnings: list[
        Literal["BLANK", "DUPLICATE", "LOW_CONFIDENCE", "OCR_FAILED"]
    ] = Field(default_factory=list, max_length=4)
    text: str = Field(default="", max_length=20000)
    reviewed: bool = False
    skip: bool = False
    pronunciation: dict[str, str] = Field(default_factory=dict, max_length=100)
    synthesisVariant: int = Field(default=0, ge=0, le=1000000)
    breakBefore: bool = False


class ResourceDraft(Strict):
    title: str = Field(min_length=1, max_length=200)
    aliases: list[str] = Field(default_factory=list, max_length=20)
    language: Literal["zh", "en", "bilingual"] = "zh"
    edition: str = Field(default="", max_length=100)
    author: str = Field(default="", max_length=100)
    publisher: str = Field(default="", max_length=100)
    isbn: str = Field(default="", max_length=20)
    source: str = Field(default="家长提供", max_length=500)
    tags: list[str] = Field(default_factory=list, max_length=30)
    minAge: int = Field(default=3, ge=0, le=18)
    maxAge: int = Field(default=12, ge=0, le=18)
    englishLevel: Literal["beginner", "basic", "intermediate"] = "beginner"
    favorite: bool = False
    coverAsset: str = Field(default="", max_length=64)
    audioAsset: str = Field(default="", max_length=64)
    complete: bool = False
    excerpt: str = Field(default="", max_length=200)
    auditioned: bool = False
    voice: Voice = Field(default_factory=Voice)
    pages: list[Page] = Field(default_factory=list, max_length=300)
    voiceSource: Literal["shared", "custom"] = "custom"
    readingMode: Literal["follow_pages", "continuous"] = "follow_pages"

    @model_validator(mode="after")
    def age_range(self):
        if self.maxAge < self.minAge:
            raise ValueError("最大适龄不能小于最小适龄")
        return self


class ResourceCreate(Strict):
    kind: Literal["book", "story", "song", "dialogue"]
    draft: ResourceDraft


class ResourceEdit(Strict):
    expectedVersion: int = Field(ge=1)
    draft: ResourceDraft


class Publish(Strict):
    expectedVersion: int = Field(ge=1)
    requestId: str = Field(min_length=16, max_length=80)


class Lookup(Strict):
    text: str = Field(min_length=1, max_length=4000)
    language: str = Field(default="", max_length=20)
    edition: str = Field(default="", max_length=100)


class Progress(Strict):
    revisionId: str
    segmentId: str
    offsetMs: int = Field(ge=0, le=86400000)
    seq: int = Field(ge=0)


class Memory(Strict):
    content: str = Field(min_length=1, max_length=300)
    source: str = Field(default="", max_length=300)
    expires: float = Field(default=0, ge=0)
    allowCloud: bool = False


class MemoryDecision(Strict):
    state: Literal["approved", "rejected", "deleted"]
    content: str | None = Field(default=None, min_length=1, max_length=300)
    expires: float | None = Field(default=None, ge=0)
    allowCloud: bool = False


class Control(Strict):
    requestId: str = Field(min_length=16, max_length=80)
    action: Literal[
        "stop", "pause", "resume", "play", "playlist", "download", "remove_download"
    ]
    resourceId: str = Field(default="", pattern=r"^(?:[a-f0-9]{32})?$")
    playlistId: str = Field(default="", pattern=r"^(?:[a-f0-9]{32})?$")


class Recover(Strict):
    invite: str = Field(min_length=20, max_length=200)
