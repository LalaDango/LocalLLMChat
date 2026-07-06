#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
FLM ツール検証ハーネス v1.0 (2026-07-07)
LocalLLMChat の buildApiMessages() が送るペイロードを忠実再現し、
gemma4-it:e4b のツール挙動を自動測定する。

測定項目:
  [A] 発火率テスト: Step1 でツールを呼ぶか (PASS = finish_reason == "tool_calls")
  [B] 結果追従テスト: gemma4 変換済み履歴を注入し、ツール結果を踏まえた応答を返すか

使い方:
  pip install requests
  python flm_tool_harness.py                # 全テスト実行 (各条件 N_TRIALS 回)
  python flm_tool_harness.py --only fire    # 発火率テストのみ
  python flm_tool_harness.py --only follow  # 結果追従テストのみ
  python flm_tool_harness.py -n 10          # 試行回数を変更

注意:
  - 実行すると FLM の checkpoint スロットが上書きされるため、
    アプリ側の次ターンは全量 prefill になる (仕様・許容)
  - NPU はシングルロックなのでリクエストは直列実行 (このスクリプトは並列化しない)
  - 生ログは results_YYYYMMDD_HHMMSS.jsonl に全件保存される
"""

import argparse
import json
import os
import sys
import time
from datetime import datetime
from pathlib import Path

try:
    import requests
except ImportError:
    sys.exit("requests が必要です: pip install requests")

# ============================================================
# 設定 (環境に合わせてここだけ変える)
# ============================================================
# FLM serve のURL。環境変数 FLM_BASE_URL で上書き可 (例: Tailscale 経由の実IP)
BASE_URL = os.environ.get("FLM_BASE_URL", "http://localhost:52625")
MODEL = "gemma4-it:e4b"
N_TRIALS = 5          # 各条件の試行回数
TIMEOUT_SEC = 300     # read timeout (prefill が長い時用、アプリと同じ 300s)

# ============================================================
# アプリ忠実再現部 (Claude Code 提供仕様 2026-07-07 そのまま)
# ここを変えると「スクリプトでは通るのにアプリで落ちる」が起きるので触らない
# ============================================================
TOOL_GUIDANCE_PROMPT = """# ツール使用ルール
- ツール呼び出し後、role が tool のメッセージで実行結果が返ってくる。次の応答は必ずその結果の内容を踏まえて書くこと。
- ask_user_question の結果の answer にはユーザーの回答が入っている（"User selected: " は選択肢の選択、"User's custom answer: " は自由記述、"User cancelled" はキャンセル）。回答を受け取ったら同じ質問を本文で繰り返さず、その回答に対する応答（正誤判定・次の処理など）を返すこと。
- get_datetime の結果の datetime/date/time が現在日時。日時に関する質問にはこの値を使って答えること。"""

# tools 配列 (Claude Code 提供の Gson フィールド順そのまま。dict は挿入順を保持するので
# json= で送信すると同じキー順で直列化される)
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
    "max_tokens": 8192,
    "top_k": 40,
    "repeat_penalty": 1.1,
    "frequency_penalty": 0.2,
    "presence_penalty": 0.0,
    "stream": False,  # v0.9.43 は非ストリームでもキャッシュ有効 (Claude Code 確認済み)
}

# ツール結果の固定注入文字列 (Gson コンパクト形式・キー順固定・スペースなし)
RESULT_ASK = '{"answer":"User selected: Multi-Head Attention","cancelled":false}'
RESULT_DT = '{"datetime":"2026-07-07T21:30:00+09:00","timezone":"Asia/Tokyo","date":"2026-07-07","time":"21:30:00","day_of_week":"TUESDAY"}'

# クイズ履歴用の args (モデル出力の再現。再シリアライズ禁止・原文のまま埋め込む)
QUIZ_ARGS = r'{"options":["Self-Attention","Multi-Head Attention","Positional Encoding","Feed-Forward Network"],"question":"Transformerモデルにおいて、複数の異なる表現力を持つアテンションメカニズムを並行して適用し、それぞれの出力を結合する仕組みは何と呼ばれますか？"}'
QUIZ_QUESTION_CORE = "並行して適用し、それぞれの出力を結合する仕組み"  # 質問再掲検出用の部分文字列
DT_ARGS = "{}"


def normalize(text: str) -> str:
    """U+3000 → 半角スペース (アプリの normalizeForApi() 相当)"""
    return text.replace("　", " ")


def tool_result_user_msg(entries):
    """gemma4 変換: ツール結果のマージ済み user メッセージを構築
    entries: [(tool_name, args_json_str, result_str), ...]
    """
    lines = [f"[ツール {name}({args}) の実行結果] {result}" for name, args, result in entries]
    return {"role": "user", "content": normalize("\n".join(lines) + "\nこの結果を踏まえて応答してください")}


def build_messages(user_prompt=None, history=None):
    msgs = [{"role": "system", "content": normalize(TOOL_GUIDANCE_PROMPT)}]
    if history:
        msgs.extend(history)
    if user_prompt is not None:
        msgs.append({"role": "user", "content": normalize(user_prompt)})
    return msgs


def call_flm(messages):
    body = dict(FIXED_PARAMS)
    body.update({"model": MODEL, "messages": messages, "tools": TOOLS})
    t0 = time.time()
    r = requests.post(f"{BASE_URL}/v1/chat/completions", json=body, timeout=(15, TIMEOUT_SEC))
    elapsed = time.time() - t0
    r.raise_for_status()
    data = r.json()
    choice = data["choices"][0]
    msg = choice.get("message", {})
    return {
        "finish_reason": choice.get("finish_reason"),
        "content": msg.get("content") or "",
        "tool_calls": msg.get("tool_calls") or [],
        "usage": data.get("usage", {}),
        "elapsed_sec": round(elapsed, 1),
    }


# ============================================================
# テスト定義
# ============================================================

# [A] 発火率テスト: 実機で観測した揺れ (日本語曖昧 → 不発 / 英語明示 → 発火) を条件化
FIRE_CONDITIONS = [
    ("JA-曖昧", "アテンション機構についてツールでクイズ出して"),
    ("JA-明示", "アテンション機構についてクイズを出してください。必ず ask_user_question ツールを使って出題すること。"),
    ("EN-明示", "Please create a quiz about attention mechanisms using the ask_user_question tool."),
    ("JA-datetime", "今何時？"),
]

# [B] 結果追従テスト: 変換済み履歴を注入して Step3 相当を測る
FOLLOW_CASES = {
    # クイズ: Step1 本文なし → assistant スキップ → user 連続 (実機で通った形)
    "quiz-user連続": {
        "history": [
            {"role": "user", "content": normalize("Please create a quiz about attention mechanisms using the ask_user_question tool.")},
            tool_result_user_msg([("ask_user_question", QUIZ_ARGS, RESULT_ASK)]),
        ],
        # 正解を選んだ想定 (Multi-Head Attention が正答) → 正解判定系キーワード
        "pass_keywords": ["正解", "正しい", "その通り", "はい", "Multi-Head"],
        "fail_if_contains": [QUIZ_QUESTION_CORE],  # 質問再掲 = FAIL
    },
    "datetime": {
        "history": [
            {"role": "user", "content": "今何時？"},
            tool_result_user_msg([("get_datetime", DT_ARGS, RESULT_DT)]),
        ],
        "pass_keywords": ["21:30", "21時30分", "9時30分", "午後9時30分"],
        "fail_if_contains": [],
    },
}


def judge_follow(case, res):
    """PASS / FAIL / GRAY の3値判定 (緩め。GRAY は目視用)"""
    content = res["content"]
    if res["finish_reason"] == "tool_calls" and not content:
        return "GRAY"  # 判定せず即次ツール呼び出し (多段ループでは合法だが単発では灰色)
    # 判定キーワードを先に評価する (2026-07-07 修正):
    # 「正解です。<解説で質問文を引用>」が FAIL に誤爆した実測例への対応。
    # 正誤判定があれば質問文の引用は解説とみなし、判定なしの質問再掲のみ FAIL
    if any(kw in content for kw in case["pass_keywords"]):
        return "PASS"
    for bad in case["fail_if_contains"]:
        if bad in content:
            return "FAIL"
    return "GRAY"


# ============================================================
# 実行部
# ============================================================

def run(only=None, n_trials=N_TRIALS):
    log_path = Path(f"results_{datetime.now():%Y%m%d_%H%M%S}.jsonl")
    logf = log_path.open("w", encoding="utf-8")

    def log(record):
        logf.write(json.dumps(record, ensure_ascii=False) + "\n")
        logf.flush()

    summary = []

    if only in (None, "fire"):
        print(f"\n=== [A] 発火率テスト (各 {n_trials} 回) ===")
        for label, prompt in FIRE_CONDITIONS:
            hits = 0
            for i in range(n_trials):
                res = call_flm(build_messages(user_prompt=prompt))
                fired = res["finish_reason"] == "tool_calls"
                hits += fired
                mark = "🔫" if fired else "・"
                print(f"  {label} #{i+1}: {mark} finish={res['finish_reason']} "
                      f"({res['elapsed_sec']}s, prefill={res['usage'].get('prompt_tokens','?')}tok)")
                log({"test": "fire", "cond": label, "trial": i + 1, "fired": fired, **res})
            rate = f"{hits}/{n_trials}"
            summary.append(("発火率", label, rate))
            print(f"  → {label}: {rate}")

    if only in (None, "follow"):
        print(f"\n=== [B] 結果追従テスト (各 {n_trials} 回) ===")
        for label, case in FOLLOW_CASES.items():
            counts = {"PASS": 0, "FAIL": 0, "GRAY": 0}
            for i in range(n_trials):
                res = call_flm(build_messages(history=case["history"]))
                verdict = judge_follow(case, res)
                counts[verdict] += 1
                head = res["content"][:60].replace("\n", " ")
                print(f"  {label} #{i+1}: {verdict} 「{head}…」")
                log({"test": "follow", "cond": label, "trial": i + 1, "verdict": verdict, **res})
            summary.append(("結果追従", label, f"PASS {counts['PASS']} / FAIL {counts['FAIL']} / GRAY {counts['GRAY']}"))
            print(f"  → {label}: {counts}")

    logf.close()
    print("\n" + "=" * 50)
    print("📊 サマリ")
    for kind, label, result in summary:
        print(f"  [{kind}] {label}: {result}")
    print(f"\n生ログ: {log_path} (GRAY 判定はここを目視)")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", choices=["fire", "follow"], default=None)
    ap.add_argument("-n", type=int, default=N_TRIALS, help="各条件の試行回数")
    args = ap.parse_args()
    run(only=args.only, n_trials=args.n)
