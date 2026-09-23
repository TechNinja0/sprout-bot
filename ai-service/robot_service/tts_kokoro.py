"""Kokoro 兼容适配器。没有语气指令能力，能力目录中明确禁用。"""

from .tts import wav_result


class KokoroProvider:
    def __init__(self, path):
        self.path, self.models = path, {}

    def synthesize(self, request):
        import sherpa_onnx as so

        sid = {"zh-girl": 45, "zh-boy": 50, "en-us": 3, "en-gb": 20}[request.voice]
        dialect = "gb" if sid == 20 else "us"
        if dialect not in self.models:
            p = self.path
            self.models[dialect] = so.OfflineTts(
                so.OfflineTtsConfig(
                    model=so.OfflineTtsModelConfig(
                        kokoro=so.OfflineTtsKokoroModelConfig(
                            model=str(p / "model.onnx"),
                            voices=str(p / "voices.bin"),
                            tokens=str(p / "tokens.txt"),
                            data_dir=str(p / "espeak-ng-data"),
                            dict_dir=str(p / "dict"),
                            lexicon=",".join(
                                str(p / n)
                                for n in (f"lexicon-{dialect}-en.txt", "lexicon-zh.txt")
                            ),
                        ),
                        num_threads=2,
                    ),
                    max_num_sentences=1,
                    rule_fsts=",".join(
                        str(p / n)
                        for n in ("date-zh.fst", "number-zh.fst", "phone-zh.fst")
                    ),
                )
            )
        config = so.GenerationConfig()
        config.sid, config.speed, config.silence_scale = sid, request.speed, 0.2
        audio = self.models[dialect].generate(request.text, config)
        return wav_result(audio.samples, audio.sample_rate)
