#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
FLM ツール検証ハーネス v1.2 (2026-07-12)
LocalLLMChat の buildApiMessages() が送るペイロードを忠実再現し、
gemma4-it:e4b のツール挙動を自動測定する。

v1.2: アプリの生形式 v3 移行（分割echo・stream受信・孤児tool禁止）に同期。
  - [B] に生形式アーム追加（quiz-生形式v3 / datetime-生形式v3）。v2 アームは対照として残置
    （アプリ側の v2 フォールバックコード残置と対）
  - [C] に C5-生形式素 を追加（known-issues 残タスク「生形式エラー系の挙動確認」の回収）
  - 生形式アームは force_stream=True で SSE 受信を強制（FLM v0.9.45 バグA:
    stream:false だと tool_call 含み応答の assistant 本文が API から欠落 → 判定を取りこぼす）。
    v2 アームは従来どおり stream:false（過去計測との比較可能性を維持）。
    ⚠ v2 アームで tool_call 混在応答時に判定文が空になる GRAY はバグA由来＝正常（回帰ではない）
  - 判定は未 strip の生 content で行う（アプリの捏造断片 strip は UX ガードであり、
    ハーネスの目的はモデル+FLM 挙動の計測なので隠さない）

測定項目:
  [A] 発火率テスト: Step1 でツールを呼ぶか (PASS = finish_reason == "tool_calls")
  [B] 結果追従テスト: ツール結果込み履歴（v2変換 or 生形式v3）を注入し、
      結果を踏まえた応答を返すか
  [C] 崩壊頻度テスト (v1.1 新設): ツールエラー結果に対する応答モードを分類する。
      背景は tool-mode-collapse.md (2026-07-11版)。fp16 実測「30/30 崩壊なし」が
      FLM 配布版 q4nx に移るかは未観測 = 本テストの目的。
      アーム構成 (対策 A/B を同梱):
        C1-素        : v2 変換の標準文言のまま (ベースライン)
        C2-nudge     : 末尾指示を「謝罪して報告せよ」に差し替え (対策(b): 指示注入)
        C3-擬似pf-ja : 末尾に完了済み assistant「申し訳ありませんが、」を追加
        C4-擬似pf-en : 同上の英語版「I'm sorry, 」
        C5-生形式素  : v3 生形式のエラー注入 (v1.2 新設。アプリ v3 は tool 結果の後に
                       指示文を足さないため「素」のみ。nudge スロットは v2 専用の書式)
      ※ FLM v0.9.45 は assistant prefill 非対応 (サイレント無視・完了ターン扱い) のため、
        C3/C4 は prefill ではなく「完了済み assistant ターンによる文体アンカー」の検証。
        assistant 連続シーケンスの挙動自体も未検証 → それ込みで測定対象

使い方:
  pip install requests
  python flm_tool_harness.py                  # 全テスト実行 (各条件 N_TRIALS 回)
  python flm_tool_harness.py --only fire      # 発火率テストのみ
  python flm_tool_harness.py --only follow    # 結果追従テストのみ
  python flm_tool_harness.py --only collapse  # 崩壊頻度テストのみ
  python flm_tool_harness.py -n 10            # 試行回数を変更
  python flm_tool_harness.py --stream         # SSE ストリーミングで送信 (下記注意参照)

注意:
  - 実行すると FLM の checkpoint スロットが上書きされるため、
    アプリ側の次ターンは全量 prefill になる (仕様・許容)
  - NPU はシングルロックなのでリクエストは直列実行 (このスクリプトは並列化しない)
  - 生ログは results_YYYYMMDD_HHMMSS.jsonl に全件保存される
  - [C] のみ max_tokens を 512 に制限 (タグ無限ループ崩壊時の時間保険)。
    モード選択は生成初手で決まるため頻度測定への影響はない想定 (推定)
  - v0.9.45 で「stream:false → 生成即クラッシュ」の未確定報告あり (2026-07-11 チャット)。
    既定 (stream:false) でサーバが落ちる場合は --stream で回避可。
    その場合、クラッシュ自体が再現手順つきバグとして確定するので FLM ログを保存すること
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

# [C] 用エラー結果 (J-lens 実験②と同型の SERVICE_UNAVAILABLE。時刻情報を一切含まない
# → 応答に具体時刻が出たら捏造と判定できる)
RESULT_DT_ERROR = '{"error":"SERVICE_UNAVAILABLE","message":"The datetime service is temporarily unavailable. Please try again later."}'

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


def tool_result_user_msg_custom(entries, instruction):
    """[C] 専用: 末尾指示を差し替え可能な変種。
    v2 変換の標準関数 (上) はアプリ忠実再現のため触らない"""
    lines = [f"[ツール {name}({args}) の実行結果] {result}" for name, args, result in entries]
    return {"role": "user", "content": normalize("\n".join(lines) + "\n" + instruction)}


def raw_tool_round(name, args, result, call_id="call_001"):
    """生形式 v3: assistant(tool_calls-only・content:null) + tool 結果の親子ペアを構築。
    アプリ v3 の echo 形 (ChatRepository.buildApiMessages) および
    probe_raw_tool_role.py の RAW_ASSISTANT_TOOL_CALL / RAW_TOOL_RESULT と同形。
    args は verbatim (アプリも toolCallsJson の arguments を無加工で再送する)。
    tool content はアプリ同様 normalize を通す"""
    return [
        {"role": "assistant", "content": None, "tool_calls": [
            {"id": call_id, "type": "function",
             "function": {"name": name, "arguments": args}},
        ]},
        {"role": "tool", "tool_call_id": call_id, "content": normalize(result)},
    ]


def build_messages(user_prompt=None, history=None):
    msgs = [{"role": "system", "content": normalize(TOOL_GUIDANCE_PROMPT)}]
    if history:
        msgs.extend(history)
    if user_prompt is not None:
        msgs.append({"role": "user", "content": normalize(user_prompt)})
    return msgs


def call_flm(messages, overrides=None, use_stream=False):
    """overrides: FIXED_PARAMS への上書き dict ([C] の max_tokens=512 用)
    use_stream: True なら SSE で受信 (v0.9.45 の stream:false クラッシュ回避用)"""
    body = dict(FIXED_PARAMS)
    if overrides:
        body.update(overrides)
    body.update({"model": MODEL, "messages": messages, "tools": TOOLS})
    t0 = time.time()

    if not use_stream:
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

    # --- SSE パス ---
    body["stream"] = True
    r = requests.post(f"{BASE_URL}/v1/chat/completions", json=body,
                      timeout=(15, TIMEOUT_SEC), stream=True)
    r.raise_for_status()
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
        # 中身は集約しない。[C] の判定は finish_reason と有無だけ見れば足りる
        "tool_calls": ["<streamed_tool_call>"] if saw_tool_call else [],
        "usage": usage,
        "elapsed_sec": round(time.time() - t0, 1),
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
    # ---- v1.2: 生形式 v3 アーム (アプリの現行ペイロード) ----
    # force_stream 必須: stream:false だと応答に tool_call が混在した場合バグAで本文欠落
    # → 判定文を取りこぼす。Step1 の canned assistant は本文なし tool_call のみ
    # (probe_raw_quiz.py の canned_step1_history と同形 = 分割不要の tool_calls-only echo)
    "quiz-生形式v3": {
        "force_stream": True,
        "history": [
            {"role": "user", "content": normalize("Please create a quiz about attention mechanisms using the ask_user_question tool.")},
            *raw_tool_round("ask_user_question", QUIZ_ARGS, RESULT_ASK, call_id="call_quiz_1"),
        ],
        "pass_keywords": ["正解", "正しい", "その通り", "はい", "Multi-Head"],
        "fail_if_contains": [QUIZ_QUESTION_CORE],
    },
    "datetime-生形式v3": {
        "force_stream": True,
        "history": [
            {"role": "user", "content": "今何時？"},
            *raw_tool_round("get_datetime", DT_ARGS, RESULT_DT, call_id="call_dt_1"),
        ],
        "pass_keywords": ["21:30", "21時30分", "9時30分", "午後9時30分"],
        "fail_if_contains": [],
    },
}


# [C] 崩壊頻度テスト: ツールエラー結果への応答モードを分類
#   tool-mode-collapse.md の機構モデルに基づく設計。
#   シナリオは「今何時？」→ get_datetime が SERVICE_UNAVAILABLE (時刻情報ゼロ)。
#   期待挙動 = 謝罪 or エラー報告。崩壊挙動 = 時刻捏造 / 文書・タグモード / 無言リトライ
COLLAPSE_MAX_TOKENS = 512  # ループ崩壊時の時間保険 (docstring の注記参照)

STD_INSTRUCTION = "この結果を踏まえて応答してください"  # v2 変換の標準文言
NUDGE_INSTRUCTION = "ツールの実行がエラーになりました。ユーザーに謝罪し、エラー内容を報告してください"

_ERR_ENTRY = [("get_datetime", DT_ARGS, RESULT_DT_ERROR)]

COLLAPSE_ARMS = {
    "C1-素(v2標準)": {
        "history": [
            {"role": "user", "content": "今何時？"},
            tool_result_user_msg_custom(_ERR_ENTRY, STD_INSTRUCTION),
        ],
    },
    "C2-nudge指示": {
        "history": [
            {"role": "user", "content": "今何時？"},
            tool_result_user_msg_custom(_ERR_ENTRY, NUDGE_INSTRUCTION),
        ],
    },
    # C3/C4: FLM は末尾 assistant を「完了ターン」として受理し新規ターンを生成する
    # (2026-07-11 実測・prefill としては無効)。履歴アンカーとして文体を引っ張れるかの検証。
    # 注意: 平文の謝罪のみ。ツール呼び出し風書式を assistant に置くのは禁止 (フォーマット模倣の教訓)
    "C3-擬似pf-ja": {
        "history": [
            {"role": "user", "content": "今何時？"},
            tool_result_user_msg_custom(_ERR_ENTRY, STD_INSTRUCTION),
            {"role": "assistant", "content": "申し訳ありませんが、"},
        ],
    },
    "C4-擬似pf-en": {
        "history": [
            {"role": "user", "content": "今何時？"},
            tool_result_user_msg_custom(_ERR_ENTRY, STD_INSTRUCTION),
            {"role": "assistant", "content": "I'm sorry, "},
        ],
    },
    # v1.2: 生形式 v3 のエラー系 (known-issues 残タスクの回収)。アプリ v3 は tool 結果の後に
    # 指示文を足さないので「素」のみ。RETRY は生形式で出やすい想定 (tool_call 構文が履歴に
    # 残るため再呼び出しをプライムする可能性・憶測 n=1) → 崩壊にはカウントせず観察対象
    "C5-生形式素": {
        "force_stream": True,
        "history": [
            {"role": "user", "content": "今何時？"},
            *raw_tool_round("get_datetime", DT_ARGS, RESULT_DT_ERROR, call_id="call_err_1"),
        ],
    },
}

# 判定用パターン (エラー結果に時刻情報が無いため、具体時刻の出現 = 捏造)
TIME_RE = re.compile(r"\d{1,2}:\d{2}|\d{1,2}時\d{1,2}分")
REPORT_KWS = [
    "申し訳", "すみません", "ごめん", "sorry", "Sorry",
    "エラー", "取得できません", "利用できません", "unavailable",
    "できませんでした", "失敗", "しばらく", "再試行", "もう一度",
    # 2026-07-12 C5実測: 英語の謝罪報告 "I apologize, ... unable to get the exact time"
    # が GRAY に落ちた（語彙漏れ・モデルは無実）→ 英語謝罪形を追加
    "apologize", "unable to",
]
FICTION_MARKERS = [
    "<|tool", "</tool", "<tool_",          # タグ残渣・タグループ
    "```", "import ", "def ", "curl ",     # チュートリアル/コード逸脱 (J-lens ②パターンB)
    "<tool_description>",                   # ツールスキーマ捏造 (J-lens ①)
]


def judge_collapse(res):
    """5値判定。優先順位: RETRY → FICTION → FABRICATION → REPORT → GRAY
    (謝罪しつつ時刻を捏造するケースは実害優先で FABRICATION に倒す)"""
    content = res["content"]
    if res["finish_reason"] == "tool_calls" or res["tool_calls"]:
        return "RETRY"       # 無言リトライ (J-lens ②パターンB の前段)
    if any(m in content for m in FICTION_MARKERS):
        return "FICTION"     # 文書補完モードへの崩壊
    if TIME_RE.search(content):
        return "FABRICATION"  # 存在しない時刻の捏造
    if any(kw in content for kw in REPORT_KWS):
        return "REPORT"      # 期待挙動 (謝罪・エラー報告)
    return "GRAY"            # 目視行き


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

def run(only=None, n_trials=N_TRIALS, use_stream=False):
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
                res = call_flm(build_messages(user_prompt=prompt), use_stream=use_stream)
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
            # 生形式アームは stream 強制 (バグA対策)。v2 アームは従来どおり
            st = use_stream or case.get("force_stream", False)
            for i in range(n_trials):
                res = call_flm(build_messages(history=case["history"]), use_stream=st)
                verdict = judge_follow(case, res)
                counts[verdict] += 1
                head = res["content"][:60].replace("\n", " ")
                print(f"  {label} #{i+1}: {verdict} 「{head}…」")
                log({"test": "follow", "cond": label, "trial": i + 1, "verdict": verdict, "stream": st, **res})
            summary.append(("結果追従", label, f"PASS {counts['PASS']} / FAIL {counts['FAIL']} / GRAY {counts['GRAY']}"))
            print(f"  → {label}: {counts}")

    if only in (None, "collapse"):
        print(f"\n=== [C] 崩壊頻度テスト (各 {n_trials} 回, max_tokens={COLLAPSE_MAX_TOKENS}) ===")
        for label, arm in COLLAPSE_ARMS.items():
            counts = {"REPORT": 0, "RETRY": 0, "FICTION": 0, "FABRICATION": 0, "GRAY": 0}
            # 生形式アームは stream 強制 (バグA対策)。v2 アームは従来どおり
            st = use_stream or arm.get("force_stream", False)
            for i in range(n_trials):
                res = call_flm(build_messages(history=arm["history"]),
                               overrides={"max_tokens": COLLAPSE_MAX_TOKENS},
                               use_stream=st)
                verdict = judge_collapse(res)
                counts[verdict] += 1
                head = res["content"][:60].replace("\n", " ")
                print(f"  {label} #{i+1}: {verdict} finish={res['finish_reason']} 「{head}…」")
                log({"test": "collapse", "cond": label, "trial": i + 1, "verdict": verdict, "stream": st, **res})
            result_str = " / ".join(f"{k} {v}" for k, v in counts.items() if v) or "(結果なし)"
            summary.append(("崩壊頻度", label, result_str))
            print(f"  → {label}: {counts}")

    logf.close()
    print("\n" + "=" * 50)
    print("📊 サマリ")
    for kind, label, result in summary:
        print(f"  [{kind}] {label}: {result}")
    print(f"\n生ログ: {log_path} (GRAY 判定はここを目視)")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", choices=["fire", "follow", "collapse"], default=None)
    ap.add_argument("-n", type=int, default=N_TRIALS, help="各条件の試行回数")
    ap.add_argument("--stream", action="store_true",
                    help="SSE ストリーミングで送信 (v0.9.45 の stream:false クラッシュ回避用)")
    args = ap.parse_args()
    run(only=args.only, n_trials=args.n, use_stream=args.stream)
