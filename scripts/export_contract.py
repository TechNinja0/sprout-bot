#!/usr/bin/env python3
"""从当前Pydantic路由导出公开契约，不读取实际家庭运行目录。"""

import json
import tempfile
from pathlib import Path

from robot_service.app import create_app

ROOT = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix="robot-contract-") as root:
    contract = create_app(root).openapi()
(ROOT / "protocol/openapi.json").write_text(
    json.dumps(contract, ensure_ascii=False, indent=2) + "\n"
)
print("protocol/openapi.json 已更新")
