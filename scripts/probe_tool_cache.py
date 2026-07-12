#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
FLM ツール会話キャッシュ一致プローブ (2026-07-11)
probe_raw_tool_role.py の続編。flm_tool_harness.py v1.1 の流儀を踏襲。

目的:
  生 OpenAI 形式のツール会話 (v0.9.45 で読解 5/5 確認済み) が checkpoint の
  プレフィックス一致を成立させるか = 「ツール会話は毎ターン全量 prefill」税
  (known-issues.md) が生形式なら消えるかを判定する。
  v2 変換レシピを退役できるかどうかの最大の実益がここで決まる。

背景 (constraints.md / known-issues.md):
  - checkpoint 照合は「最終 user 発言を除く履歴全体の完全一致」(トークン単位)
  - checkpoint にはモデル生出力 (<|tool_call> 構文込み) が刻まれる。
    v2 変換は履歴を別テキストに置換するためトークン列が不一致 → 毎ターン全量 prefill
  - 生形式なら FLM 自身のテンプレ展開が生成時の形と一致する可能性がある (五分五分・推定)。
    不一致要因の候補: 生成末尾の残渣/終端トークン、tool メッセージの展開位置、
    「最終 user 発言」扱いの解釈 (最終が role:"tool" の場合の挙動は未知)

アーム構成 (各アーム独立に turn1 から回す。シングルキャッシュスロットのため):
  Arm-raw : turn1 発火 → turn2 生形式 (assistant(tool_calls) 原文再送 + role:"tool")
            → turn3 フォローアップ user
  Arm-v2  : turn1 発火 → turn2 v2 変換 (assistant スキップ + マージ済み user)
            → turn3 フォローアップ user
            ※ v2 の turn2 ミスは既知想定 (対照)。turn3 は「毎ターン全量」という
              既存記述が本当に毎ターンか (ツールラウンド直後だけではないか) の検証

判定:
  スクリプト側は usage 全フィールドと prompt_tokens・所要時間を記録するのみ。
  決定打は FLM コンソールの「Prompt cache miss」行の有無。
  各リクエスト直前に [MARK] 行 (ローカル時刻つき) を出すので、FLM ログの
  Time stamp と突き合わせて turn2/turn3 のミス有無を読む。
  usage に KV 系フィールドがあれば constraints.md の余剰式
  (余剰 = prompt + completion + 前ターンKV − 今回KV) も併用可。

使い方:
  python probe_tool_cache.py              # 両アーム実行
  python probe_tool_cache.py --only raw
  python probe_tool_cache.py --only v2
  python probe_tool_cache.py --stream     # SSE 送信 (tool_calls はデルタ集約で復元)

注意:
  - 実行で checkpoint スロットが上書きされる (SM 温めセッション不在時に実行)
  - turn1 が発火しない場合は 3 回まで再試行し、それでも不発ならアーム中止
  - 生ログは tool_cache_results_YYYYMMDD_HHMMSS.jsonl に全件保存
    (turn2/turn3 の送信メッセージ全文も保存 → FLM 報告や再現の添付資料に使える)
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
# 設定 (flm_tool_harness.py v1.1 / probe_raw_tool_role.py と同一)
# ============================================================
BASE_URL = os.environ.get("FLM_BASE_URL", "http://localhost:52625")
MODEL = "gemma4-it:e4b"
TIMEOUT_SEC = 300
FIRE_RETRY = 3  # turn1 不発時の再試行上限

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
    "max_tokens": 512,
    "top_k": 40,
    "repeat_penalty": 1.1,
    "frequency_penalty": 0.2,
    "presence_penalty": 0.0,
    "stream": False,
}

RESULT_DT = '{"datetime":"2026-07-07T21:30:00+09:00","timezone":"Asia/Tokyo","date":"2026-07-07","time":"21:30:00","day_of_week":"TUESDAY"}'
USER_PROMPT_T1 = "今何時？"          # [A] JA-datetime 5/5 発火の実績条件
USER_PROMPT_T3 = "ありがとう。ちなみに今日は何日？"  # 結果内の date から答えられる追い質問


def normalize(text: str) -> str:
    """U+3000 → 半角スペース (アプリの normalizeForApi() 相当)"""
    return text.replace("　", " ")


def tool_result_user_msg(entries):
    """gemma4 v2 変換 (ハーネスと同一実装)"""
    lines = [f"[ツール {name}({args}) の実行結果] {result}" for name, args, result in entries]
    return {"role": "user", "content": normalize("\n".join(lines) + "\nこの結果を踏まえて応答してください")}


def build_messages(history):
    return [{"role": "system", "content": normalize(TOOL_GUIDANCE_PROMPT)}] + history


# ============================================================
# 送信部 (probe_raw_tool_role.py の call_flm + raw_message 返却 + SSE tool_calls 集約)
# ============================================================

def call_flm(messages, use_stream=False):
    body = dict(FIXED_PARAMS)
    body.update({"model": MODEL, "messages": messages, "tools": TOOLS})
    t0 = time.time()

    if not use_stream:
        r = requests.post(f"{BASE_URL}/v1/chat/completions", json=body, timeout=(15, TIMEOUT_SEC))
        elapsed = time.time() - t0
        if r.status_code >= 400:
            return {
                "finish_reason": None, "content": "", "tool_calls": [], "usage": {},
                "raw_message": None, "elapsed_sec": round(elapsed, 1),
                "http_status": r.status_code, "http_body": r.text[:500],
            }
        data = r.json()
        choice = data["choices"][0]
        msg = choice.get("message", {}) or {}
        return {
            "finish_reason": choice.get("finish_reason"),
            "content": msg.get("content") or "",
            "tool_calls": msg.get("tool_calls") or [],
            "usage": data.get("usage", {}),
            "raw_message": msg,  # ← turn2 で原文のまま再送するための完全なメッセージ
            "elapsed_sec": round(elapsed, 1),
            "http_status": r.status_code,
        }

    # --- SSE パス (tool_calls をデルタ集約して raw_message を復元) ---
    body["stream"] = True
    r = requests.post(f"{BASE_URL}/v1/chat/completions", json=body,
                      timeout=(15, TIMEOUT_SEC), stream=True)
    if r.status_code >= 400:
        return {
            "finish_reason": None, "content": "", "tool_calls": [], "usage": {},
            "raw_message": None, "elapsed_sec": round(time.time() - t0, 1),
            "http_status": r.status_code, "http_body": r.text[:500],
        }
    r.encoding = "utf-8"  # charset 無指定 SSE の latin-1 デコード事故防止
    content_parts, finish, usage = [], None, {}
    tc_acc = {}  # index → {"id","type","function":{"name","arguments"}}
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
        for tc in delta.get("tool_calls") or []:
            idx = tc.get("index", 0)
            slot = tc_acc.setdefault(idx, {"id": None, "type": "function",
                                           "function": {"name": "", "arguments": ""}})
            if tc.get("id"):
                slot["id"] = tc["id"]
            fn = tc.get("function") or {}
            if fn.get("name"):
                slot["function"]["name"] = fn["name"]
            if fn.get("arguments"):
                slot["function"]["arguments"] += fn["arguments"]
        if ch.get("finish_reason"):
            finish = ch["finish_reason"]
        if chunk.get("usage"):
            usage = chunk["usage"]
    content = "".join(content_parts)
    tool_calls = [tc_acc[i] for i in sorted(tc_acc)]
    raw_message = {"role": "assistant", "content": content or None}
    if tool_calls:
        raw_message["tool_calls"] = tool_calls
    return {
        "finish_reason": finish, "content": content, "tool_calls": tool_calls,
        "usage": usage, "raw_message": raw_message,
        "elapsed_sec": round(time.time() - t0, 1), "http_status": 200,
    }


# ============================================================
# 実行部
# ============================================================

def mark(label):
    """FLM ログの Time stamp と突き合わせるためのマーカー"""
    print(f"  [MARK {datetime.now():%H:%M:%S}] ここから → {label}")


def show(res, label):
    pt = res["usage"].get("prompt_tokens", "?")
    ct = res["usage"].get("completion_tokens", "?")
    head = (res["content"] or "")[:50].replace("\n", " ")
    print(f"    {label}: finish={res['finish_reason']} prefill={pt}tok gen={ct}tok "
          f"({res['elapsed_sec']}s)")
    if res.get("http_status", 200) >= 400:
        print(f"    ⚠ HTTP {res['http_status']}: {res.get('http_body','')[:80]}")
    elif head:
        print(f"    「{head}…」")
    extra = {k: v for k, v in res["usage"].items()
             if k not in ("prompt_tokens", "completion_tokens", "total_tokens")}
    if extra:
        print(f"    usage追加フィールド: {extra}")


def fire_turn1(use_stream, log, arm):
    """turn1: 発火するまで最大 FIRE_RETRY 回。成功時 (res) を返す"""
    for attempt in range(1, FIRE_RETRY + 1):
        mark(f"{arm} turn1 (試行{attempt})")
        res = call_flm(build_messages([{"role": "user", "content": USER_PROMPT_T1}]),
                       use_stream=use_stream)
        show(res, f"turn1#{attempt}")
        log({"arm": arm, "turn": 1, "attempt": attempt, **res})
        if res["finish_reason"] == "tool_calls" and res["tool_calls"]:
            return res
        print("    (不発 → 再試行)")
    return None


def run_arm(arm, use_stream, log):
    print(f"\n=== Arm-{arm} ===")
    t1 = fire_turn1(use_stream, log, arm)
    if t1 is None:
        print(f"  ⚠ Arm-{arm}: turn1 が {FIRE_RETRY} 回とも不発。アーム中止")
        return None

    tc = t1["tool_calls"][0]
    call_id = tc.get("id") or "call_001"
    args_verbatim = (tc.get("function") or {}).get("arguments", "{}")  # 原文のまま・再シリアライズ禁止

    # --- turn2 履歴の構築 ---
    if arm == "raw":
        history2 = [
            {"role": "user", "content": USER_PROMPT_T1},
            t1["raw_message"],  # 返却された assistant メッセージを原文のまま再送
            {"role": "tool", "tool_call_id": call_id, "content": RESULT_DT},
        ]
    else:  # v2
        history2 = [
            {"role": "user", "content": USER_PROMPT_T1},
            # v2 レシピ: assistant は本文なしならスキップ、結果はマージ済み user
            tool_result_user_msg([("get_datetime", args_verbatim, RESULT_DT)]),
        ]

    print("  ── turn2: ツール結果注入。FLM ログでこのリクエストに")
    print("     『Prompt cache miss』が出るかが本題 ──")
    mark(f"{arm} turn2")
    t2 = call_flm(build_messages(history2), use_stream=use_stream)
    show(t2, "turn2")
    log({"arm": arm, "turn": 2, "sent_messages": build_messages(history2), **t2})

    # --- turn3 履歴の構築 (turn2 応答を原文のまま積む) ---
    history3 = history2 + [
        {"role": "assistant", "content": t2["content"]},
        {"role": "user", "content": USER_PROMPT_T3},
    ]
    print("  ── turn3: フォローアップ。ここのミス有無で『毎ターン全量』か")
    print("     『ツールラウンド直後だけ』かが切り分かる ──")
    mark(f"{arm} turn3")
    t3 = call_flm(build_messages(history3), use_stream=use_stream)
    show(t3, "turn3")
    log({"arm": arm, "turn": 3, "sent_messages": build_messages(history3), **t3})

    return {"turn1": t1, "turn2": t2, "turn3": t3}


def run(only=None, use_stream=False):
    log_path = Path(f"tool_cache_results_{datetime.now():%Y%m%d_%H%M%S}.jsonl")
    logf = log_path.open("w", encoding="utf-8")

    def log(record):
        logf.write(json.dumps(record, ensure_ascii=False) + "\n")
        logf.flush()

    print(f"=== ツール会話キャッシュ一致プローブ (FLM {BASE_URL}, model={MODEL}) ===")
    print("※ FLM コンソールを並べて表示しておくこと (Prompt cache miss 行が決定打)")

    arms = [a for a in ("raw", "v2") if only is None or a == only]
    results = {}
    for arm in arms:
        results[arm] = run_arm(arm, use_stream, log)

    logf.close()
    print("\n" + "=" * 50)
    print("📊 サマリ (prefill トークン数)")
    for arm, r in results.items():
        if r is None:
            print(f"  Arm-{arm}: 中止")
            continue
        pts = [r[f"turn{i}"]["usage"].get("prompt_tokens", "?") for i in (1, 2, 3)]
        print(f"  Arm-{arm}: turn1={pts[0]} / turn2={pts[1]} / turn3={pts[2]} tok")
    print(f"\n生ログ: {log_path} (turn2/turn3 の送信メッセージ全文込み)")
    print("""
🔍 読み方 (FLM コンソールの [MARK] 対応リクエストを確認):
  raw turn2 ミスなし → 🎉 生形式でツールラウンドを跨いでキャッシュが繋がる
                        = 全量 prefill 税消滅・v2 変換退役の実益確定へ
  raw turn2 ミスあり → 生形式でも不一致。生成時 checkpoint とテンプレ再展開の
                        トークン列ズレ (終端トークン/残渣が候補)。退役の実益は減るが
                        読解 5/5 の価値は残る。ミス時の prefill 全量値も記録すること
  raw turn3          → turn2 の結果とセットで「どこで切れるか」を特定
  v2 turn2 ミスあり  → 既知想定の対照 (これが出ないなら既存理解の方を見直す)
  v2 turn3 ミスなし  → 「毎ターン全量」は言い過ぎで「ツールラウンド直後だけ」に
                        修正が必要 (known-issues.md の記述更新対象)
  ※ 500tok 級では時間差は出ない。判定はコンソールの miss 行でつけること
""")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", choices=["raw", "v2"], default=None)
    ap.add_argument("--stream", action="store_true",
                    help="SSE 送信 (tool_calls はデルタ集約で復元)")
    args = ap.parse_args()
    run(only=args.only, use_stream=args.stream)
