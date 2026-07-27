"""pytest 공용 설정.

실행: embedded/ 에서 `python -m pytest test -q`
필요: pytest (운영 requirements.txt 에는 넣지 않는다 — 개발 PC 전용)
"""

import sys
from pathlib import Path

EMBEDDED_ROOT = Path(__file__).resolve().parents[1]
if str(EMBEDDED_ROOT) not in sys.path:
    sys.path.insert(0, str(EMBEDDED_ROOT))
