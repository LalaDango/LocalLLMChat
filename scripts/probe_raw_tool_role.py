#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
FLM 素の role:"tool" プローブ (2026-07-11)
flm_tool_harness.py v1.1 の姉妹スクリプト (流儀・定数・call_flm を踏襲)。

目的:
  FLM v0.9.45 の gemma4 テンプレートが role:"tool" を落とすか (0.9.43 時代の
  真因疑い・known-issues.md「gemma4系のrole:"tool"無視問題」) を白黒判定する。
  [C] テストは v2 変換済みペイロードで走ったため、0.9.45 の素の role 処理は未確認。

アーム構成:
  R0-v2変換     : 変換済み形式 (既知の正常系)。同日・同条件のベースライン
  R1-生フル     : 生 OpenAI 形式 user → assistant(tool_calls) → role:"tool"
  R2-生tool単独 : user → role:"tool" (assistant 抜き)。R1 が assistant 側の
                  問題で転んだ場合に tool ロール単体の扱いを切り分ける

判定 (シナリオ: 「今何時？」→ get_datetime 成功結果 21:30 を注入):
  READ       : 応答に 21:30 系が出現 = ツール結果が読めている (テンプレ修正済みの示唆)
  RETRY      : 再度 tool_call = 結果が見えていない (ドロップの強い示唆。
               モデル視点では「質問あり・結果なし」なので再呼び出しは合理的挙動)
  BLIND      : 「時刻が分からない」系 = 結果が見えていない別形
  HTTP_ERROR : FLM が生形式を 400 等で拒絶 (サイレントドロップとは別の失敗クラス。
               これ自体が確定所見になる)
  GRAY       : 目視行き

⚠ 挙動判定だけでは確定にしない:
  決定打は FLM 側コンソールの RAW Output (テンプレート展開後プロンプト)。
  <|tool_response> 相当の畳み込みが入っていれば「修正済み・確定」、
  tool メッセージが丸ごと消えていれば「欠落継続・確定」。
  実行後に必ず FLM ログを確認・保存すること (③ FLM 報告の添付資料になる)。

使い方:
  python probe_raw_tool_role.py             # 全アーム n=5
  python probe_raw_tool_role.py -n 3
  python probe_raw_tool_role.py --only R1
  python probe_raw_tool_role.py --stream    # stream:false クラッシュ再発時の回避

注意 (ハーネスと同じ):
  - 実行で FLM の checkpoint スロットが上書きされる (SM 温めセッション不在時に実行)
  - NPU シングルロックのため直列実行
  - 生ログは raw_tool_role_results_YYYYMMDD_HHMMSS.jsonl に全件保存
"""

import argparse
import json
import os
import re
import sys
import time
from datetime import datetime
from pathlib import Path

try:
    import requests
except ImportError:
    sys.exit("requests が必要です: pip install requests")

# ============================================================
# 設定 (flm_tool_harness.py v1.1 と同一)
# ============================================================
BASE_URL = os.environ.get("FLM_BASE_URL", "http://localhost:52625")
MODEL = "gemma4-it:e4b"
N_TRIALS = 5
TIMEOUT_SEC = 300

TOOL_GUIDANCE_PROMPT = """# ツール使用ルール
- ツール呼び出し後、role が tool のメッセージで実行結果が返ってくる。次の応答は必ずその結果の内容を踏まえて書くこと。
- ask_user_question の結果の answer にはユーザーの回答が入っている（"User selected: " は選択肢の選択、"User's custom answer: " は自由記述、"User cancelled" はキャンセル）。回答を受け取ったら同じ質問を本文で繰り返さず、その回答に対する応答（正誤判定・次の処理など）を返すこと。
- get_datetime の結果の datetime/date/time が現在日時。日時に関する質問にはこの値を使って答えること。"""

TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "get_datetime",
            "description": "Get the current date and time. The result contains datetime/date/time/day_of_week; use these values when answering the user.",
            "parameters": {
                "type": "object",
                "properties": {
                    "timezone": {
                        "type": "string",
                        "description": "Timezone (e.g. Asia/Tokyo). Defaults to device timezone.",
                    }
                },
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "ask_user_question",
            "description": 'Ask the user a multiple-choice question and wait for their answer. Use this when you need clarification or a decision from the user to proceed. The tool result\'s \'answer\' field contains the user\'s actual answer (e.g. "User selected: <option>"). After receiving it, do not repeat the question; respond to the user\'s answer (e.g. judge correctness, then continue).',
            "parameters": {
                "type": "object",
                "properties": {
                    "question": {
                        "type": "string",
                        "description": "The question to ask the user",
                    },
                    "options": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": "List of options for the user to choose from",
                    },
                },
                "required": ["question", "options"],
            },
        },
    },
]

FIXED_PARAMS = {
    "temperature": 0.45,
    "top_p": 0.9,
    "max_tokens": 512,   # 単発報告タスクなので [C] と同じ保険水準で十分
    "top_k": 40,
    "repeat_penalty": 1.1,
    "frequency_penalty": 0.2,
    "presence_penalty": 0.0,
    "stream": False,
}

# ツール結果 (Gson コンパクト形式・原文のままハーネスから流用)
RESULT_DT = '{"datetime":"2026-07-07T21:30:00+09:00","timezone":"Asia/Tokyo","date":"2026-07-07","time":"21:30:00","day_of_week":"TUESDAY"}'
DT_ARGS = "{}"
USER_PROMPT = "今何時？"


def normalize(text: str) -> str:
    """U+3000 → 半角スペース (アプリの normalizeForApi() 相当)"""
    return text.replace("　", " ")


def tool_result_user_msg(entries):
    """gemma4 v2 変換 (R0 ベースライン用・ハーネスと同一実装)"""
    lines = [f"[ツール {name}({args}) の実行結果] {result}" for name, args, result in entries]
    return {"role": "user", "content": normalize("\n".join(lines) + "\nこの結果を踏まえて応答してください")}


# ============================================================
# アーム定義
# ============================================================

# 生 OpenAI 形式の assistant (tool_call 発行側)。content は OpenAI 慣例に合わせ null
RAW_ASSISTANT_TOOL_CALL = {
    "role": "assistant",
    "content": None,
    "tool_calls": [
        {
            "id": "call_001",
            "type": "function",
            "function": {"name": "get_datetime", "arguments": DT_ARGS},
        }
    ],
}

RAW_TOOL_RESULT = {
    "role": "tool",
    "tool_call_id": "call_001",
    "content": RESULT_DT,
}

ARMS = {
    "R0-v2変換": [
        {"role": "user", "content": USER_PROMPT},
        tool_result_user_msg([("get_datetime", DT_ARGS, RESULT_DT)]),
    ],
    "R1-生フル": [
        {"role": "user", "content": USER_PROMPT},
        RAW_ASSISTANT_TOOL_CALL,
        RAW_TOOL_RESULT,
    ],
    "R2-生tool単独": [
        {"role": "user", "content": USER_PROMPT},
        RAW_TOOL_RESULT,
    ],
}

# 判定パターン
READ_KWS = ["21:30", "21時30分", "9時30分", "午後9時30分"]
BLIND_KWS = [
    "分かりません", "わかりません", "できません", "取得できません",
    "アクセスできません", "リアルタイム", "現在の時刻を知る",
]


def judge(res):
    """優先順位: RETRY → READ → BLIND → GRAY
    (READ を BLIND より先に評価: 「取得できませんでしたが履歴によると21:30」等の
     複合文は結果が読めている側に倒す)"""
    if res["finish_reason"] == "tool_calls" or res["tool_calls"]:
        return "RETRY"
    content = res["content"]
    if any(kw in content for kw in READ_KWS):
        return "READ"
    if any(kw in content for kw in BLIND_KWS):
        return "BLIND"
    return "GRAY"


# ============================================================
# 送信部 (flm_tool_harness.py v1.1 の call_flm を踏襲 + HTTP エラー捕捉)
# ============================================================

def call_flm(messages, use_stream=False):
    body = dict(FIXED_PARAMS)
    body.update({"model": MODEL, "messages": messages, "tools": TOOLS})
    t0 = time.time()

    if not use_stream:
        r = requests.post(f"{BASE_URL}/v1/chat/completions", json=body, timeout=(15, TIMEOUT_SEC))
        elapsed = time.time() - t0
        if r.status_code >= 400:
            # 生形式の拒絶はそれ自体が所見。本文ごと記録して判定に回す
            return {
                "finish_reason": None,
                "content": "",
                "tool_calls": [],
                "usage": {},
                "elapsed_sec": round(elapsed, 1),
                "http_status": r.status_code,
                "http_body": r.text[:500],
            }
        data = r.json()
        choice = data["choices"][0]
        msg = choice.get("message", {})
        return {
            "finish_reason": choice.get("finish_reason"),
            "content": msg.get("content") or "",
            "tool_calls": msg.get("tool_calls") or [],
            "usage": data.get("usage", {}),
            "elapsed_sec": round(elapsed, 1),
            "http_status": r.status_code,
        }

    # --- SSE パス ---
    body["stream"] = True
    r = requests.post(f"{BASE_URL}/v1/chat/completions", json=body,
                      timeout=(15, TIMEOUT_SEC), stream=True)
    if r.status_code >= 400:
        return {
            "finish_reason": None,
            "content": "",
            "tool_calls": [],
            "usage": {},
            "elapsed_sec": round(time.time() - t0, 1),
            "http_status": r.status_code,
            "http_body": r.text[:500],
        }
    r.encoding = "utf-8"  # charset 無指定の SSE を latin-1 でデコードされる事故の防止
    content_parts, finish, usage, saw_tool_call = [], None, {}, False
    for line in r.iter_lines(decode_unicode=True):
        if not line or not line.startswith("data:"):
            continue
        payload = line[5:].strip()
        if payload == "[DONE]":
            break
        try:
            chunk = json.loads(payload)
        except json.JSONDecodeError:
            continue
        ch = (chunk.get("choices") or [{}])[0]
        delta = ch.get("delta") or {}
        if delta.get("content"):
            content_parts.append(delta["content"])
        if delta.get("tool_calls"):
            saw_tool_call = True
        if ch.get("finish_reason"):
            finish = ch["finish_reason"]
        if chunk.get("usage"):
            usage = chunk["usage"]
    return {
        "finish_reason": finish,
        "content": "".join(content_parts),
        "tool_calls": ["<streamed_tool_call>"] if saw_tool_call else [],
        "usage": usage,
        "elapsed_sec": round(time.time() - t0, 1),
        "http_status": 200,
    }


# ============================================================
# 実行部
# ============================================================

def build_messages(history):
    return [{"role": "system", "content": normalize(TOOL_GUIDANCE_PROMPT)}] + history


def run(only=None, n_trials=N_TRIALS, use_stream=False):
    log_path = Path(f"raw_tool_role_results_{datetime.now():%Y%m%d_%H%M%S}.jsonl")
    logf = log_path.open("w", encoding="utf-8")

    def log(record):
        logf.write(json.dumps(record, ensure_ascii=False) + "\n")
        logf.flush()

    summary = []
    arms = {k: v for k, v in ARMS.items() if only is None or k.startswith(only)}
    if not arms:
        sys.exit(f"--only {only} に一致するアームがありません (R0/R1/R2)")

    print(f"=== 素の role:\"tool\" プローブ (FLM {BASE_URL}, model={MODEL}, 各 {n_trials} 回) ===")
    for label, history in arms.items():
        counts = {}
        for i in range(n_trials):
            res = call_flm(build_messages(history), use_stream=use_stream)
            if res.get("http_status", 200) >= 400:
                verdict = "HTTP_ERROR"
                head = f"HTTP {res['http_status']}: {res.get('http_body','')[:60]}"
            else:
                verdict = judge(res)
                head = res["content"][:60].replace("\n", " ")
            counts[verdict] = counts.get(verdict, 0) + 1
            print(f"  {label} #{i+1}: {verdict} finish={res['finish_reason']} "
                  f"({res['elapsed_sec']}s, prefill={res['usage'].get('prompt_tokens','?')}tok) 「{head}…」")
            log({"test": "raw_tool_role", "cond": label, "trial": i + 1,
                 "verdict": verdict, **res})
        result_str = " / ".join(f"{k} {v}" for k, v in sorted(counts.items()))
        summary.append((label, result_str))
        print(f"  → {label}: {result_str}")

    logf.close()
    print("\n" + "=" * 50)
    print("📊 サマリ")
    for label, result in summary:
        print(f"  {label}: {result}")
    print(f"\n生ログ: {log_path}")
    print("""
🔍 読み方の目安 (挙動側のみ・確定には FLM ログ確認が必須):
  R1 が READ 優勢          → テンプレ修正済みの示唆。RAW Output で <|tool_response>
                             畳み込みを確認できれば「修正済み・確定」→ 変換層退役の検討へ
  R1 が RETRY/BLIND 優勢   → ドロップ継続の示唆。RAW Output で tool メッセージ消失を
                             確認できれば「欠落継続・確定」→ ③ FLM 報告の添付資料に
  R1 だけ HTTP_ERROR       → サイレントドロップとは別の失敗クラス (これ自体が新所見)
  R0 が READ でない        → ベースライン異常。環境かモデル状態を疑う (flm pull 再取得等)
  ※ prefill トークン数の R0/R1 差もヒント: R1 が不自然に小さければ丸ごと消えている傍証
""")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", choices=["R0", "R1", "R2"], default=None)
    ap.add_argument("-n", type=int, default=N_TRIALS, help="各条件の試行回数")
    ap.add_argument("--stream", action="store_true",
                    help="SSE ストリーミングで送信 (stream:false クラッシュ再発時の回避用)")
    args = ap.parse_args()
    run(only=args.only, n_trials=args.n, use_stream=args.stream)
