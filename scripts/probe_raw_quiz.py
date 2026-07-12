#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
FLM 生形式クイズフロー・プローブ v1.1 (2026-07-11 深夜改修)
probe_raw_tool_role.py / probe_tool_cache.py の続編。同じ流儀 (canned原文埋め込み・
[MARK]突合・JSONL全文保存)。v2変換レシピ退役 (残ピース①) の最終関門。

v1.1 追加 — ①´ echo変種検証 (アーム raw_strip):
  v1.0 のstream実測で発見したバグB (content+tool_calls混在のassistant履歴ラウンドを
  echoすると直後のtool結果への追従が3/3失敗＋キャッシュ当該ラウンド全量ミス) の
  回避策検証。raw_strip は履歴再送時、tool_calls付きassistantのcontentを剥がし
  tool_callsのみ送る (本文はアプリならDB/表示専用に回す想定)。
  推奨実行: python probe_raw_quiz.py --stream --only raw_strip -n 3
  判定: step3がJUDGE3に戻れば回避策成立＝v3実装要件が確定。
  キャッシュは剥がしecho≠本文入りcheckpointのため当該ラウンド税1回を許容 (推定)。
  ※ バグA (stream:false時のcontent欠落) があるため本検証は --stream 必須。
    stream:falseで走らせるとバグAが本文を落とし、意図せずraw_strip相当になる点に注意

目的:
  ask_user_question の多段ループを生 OpenAI 形式で回し、v2 変換と比較する。
  主エンドポイントは「正誤判定スキップ率」— 生履歴に <|tool_call> 構文が残ることで
  判定を飛ばして即次問 tool_call する挙動 (v2 で約 1/5、known-issues.md) が
  悪化しないか (再呼び出しプライム仮説・憶測 n=1 のスクリーニング)。

チェーン構造 (1試行 = 最大3リクエスト):
  [注入] user「クイズ出して(JA名指し)」+ canned tool_call (QUIZ_ARGS原文)
         + 結果 "User selected: Multi-Head Attention" (=正答)
  Step1: 生成を分類 ★主計測
         JUDGE_AND_NEXT : 判定あり + 次問 tool_call (理想挙動)
         JUDGE          : 判定のみ (良)
         SKIP           : 判定なしで次問 tool_call ★プライム仮説の計測対象
         REPEAT         : 判定なしで質問再掲 (旧症状)
         GRAY           : 目視行き
  Step2: (JUDGE のみ) user「次の問題を(名指し)」→ 発火するか
         FIRE / TEXT_QUIZ (本文で出題を始める=LocalLLMChat実機の不発形) / NO_FIRE
  Step3: (FIRE or JUDGE_AND_NEXT) 生成された新問題の options[0] を機械回答として注入
         → 判定行動が出るか (JUDGE3 / SKIP3 / GRAY3)。
         ※ options[0] が正解とは限らないため「正誤どちらかの判定文が出るか」の緩判定
  SKIP / REPEAT / 不発 / args破損はそこでチェーン打ち切り (記録して次チェーンへ)

アーム: raw (生形式・混在echo) / raw_strip (生形式・content剥がしecho) /
        v2 (変換形式・対照)。既定 各 n=10 チェーン。
おまけ計測: prefill トークン推移 (「ツールラウンドごとに1回」規則が深いチェーンでも
成り立つか。FLM コンソールの Matched X out of Y rounds 行と突合)

判断分岐 (事前登録):
  raw SKIP ≦ v2 SKIP (悪化なし) → 残ピース①クローズ、v3 生パススルー実装へ
  raw が明確に悪い (例 5/10 級)  → プライム実在。GRAY対策 A/B 実験 (system側の
                                    判定要求強化など) に合流
  ※ n=10 同士の差は 1/10 vs 3/10 程度なら偶然の範囲。スクリーニングとして読む

使い方:
  python probe_raw_quiz.py              # 両アーム n=10
  python probe_raw_quiz.py -n 5
  python probe_raw_quiz.py --only raw
  python probe_raw_quiz.py --stream

注意 (いつもの):
  - SM 温めセッション不在時に実行 (checkpoint スロットを汚す)
  - FLM コンソールを並べて表示 (キャッシュ行の突合用)
  - 生ログは raw_quiz_results_YYYYMMDD_HHMMSS.jsonl (送信メッセージ全文込み)
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
# 設定 (既存プローブ群と同一)
# ============================================================
BASE_URL = os.environ.get("FLM_BASE_URL", "http://localhost:52625")
MODEL = "gemma4-it:e4b"
N_CHAINS = 10
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
    "max_tokens": 512,
    "top_k": 40,
    "repeat_penalty": 1.1,
    "frequency_penalty": 0.2,
    "presence_penalty": 0.0,
    "stream": False,
}

# canned 素材 (ハーネス v1.1 原文のまま・再シリアライズ禁止)
QUIZ_ARGS = r'{"options":["Self-Attention","Multi-Head Attention","Positional Encoding","Feed-Forward Network"],"question":"Transformerモデルにおいて、複数の異なる表現力を持つアテンションメカニズムを並行して適用し、それぞれの出力を結合する仕組みは何と呼ばれますか？"}'
QUIZ_QUESTION_CORE = "並行して適用し、それぞれの出力を結合する仕組み"
RESULT_ASK = '{"answer":"User selected: Multi-Head Attention","cancelled":false}'

USER_PROMPT_T1 = "アテンション機構についてクイズを出してください。必ず ask_user_question ツールを使って出題すること。"
USER_PROMPT_NEXT = "次の問題をお願いします。必ず ask_user_question ツールを使って出題すること。"

# 判定キーワード (ハーネス [B] の教訓: 判定キーワードを先に評価)
JUDGE_KWS = ["正解", "正しい", "その通り", "Multi-Head"]
JUDGE3_KWS = ["正解", "不正解", "正しい", "違います", "残念", "惜しい", "その通り", "誤り"]


def normalize(text: str) -> str:
    return text.replace("　", " ")


def tool_result_user_msg(entries):
    """gemma4 v2 変換 (ハーネスと同一実装)"""
    lines = [f"[ツール {name}({args}) の実行結果] {result}" for name, args, result in entries]
    return {"role": "user", "content": normalize("\n".join(lines) + "\nこの結果を踏まえて応答してください")}


def build_messages(history):
    return [{"role": "system", "content": normalize(TOOL_GUIDANCE_PROMPT)}] + history


def make_answer_result(option_text):
    """options[0] 回答の結果 JSON を Gson コンパクト形式で構築"""
    return json.dumps({"answer": f"User selected: {option_text}", "cancelled": False},
                      separators=(",", ":"), ensure_ascii=False)


# ============================================================
# 送信部 (probe_tool_cache.py と同一: raw_message 返却 + SSE tool_calls 集約)
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
            "raw_message": msg,
            "elapsed_sec": round(elapsed, 1),
            "http_status": r.status_code,
        }

    body["stream"] = True
    r = requests.post(f"{BASE_URL}/v1/chat/completions", json=body,
                      timeout=(15, TIMEOUT_SEC), stream=True)
    if r.status_code >= 400:
        return {
            "finish_reason": None, "content": "", "tool_calls": [], "usage": {},
            "raw_message": None, "elapsed_sec": round(time.time() - t0, 1),
            "http_status": r.status_code, "http_body": r.text[:500],
        }
    r.encoding = "utf-8"
    content_parts, finish, usage = [], None, {}
    tc_acc = {}
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
# 分類
# ============================================================

def has_tc(res):
    return res["finish_reason"] == "tool_calls" or bool(res["tool_calls"])


def judge_step1(res):
    judged = any(kw in res["content"] for kw in JUDGE_KWS)
    if has_tc(res):
        return "JUDGE_AND_NEXT" if judged else "SKIP"
    if judged:
        return "JUDGE"
    if QUIZ_QUESTION_CORE in res["content"]:
        return "REPEAT"
    return "GRAY"


def judge_step2(res):
    if has_tc(res):
        return "FIRE"
    if "？" in res["content"] or "?" in res["content"]:
        return "TEXT_QUIZ"  # 本文で出題を始める = LocalLLMChat 実機の不発形
    return "NO_FIRE"


def judge_step3(res):
    judged = any(kw in res["content"] for kw in JUDGE3_KWS)
    if judged:
        return "JUDGE3"
    if has_tc(res):
        return "SKIP3"
    return "GRAY3"


def parse_next_question(res):
    """生成 tool_call から options[0] を取り出す。失敗なら None (ARGS_MALFORMED)"""
    if not res["tool_calls"]:
        return None
    tc = res["tool_calls"][0]
    fn = tc.get("function") or {}
    if fn.get("name") != "ask_user_question":
        return None
    try:
        args = json.loads(fn.get("arguments") or "")
        options = args["options"]
        if not isinstance(options, list) or not options:
            return None
        return {"tc": tc, "args_verbatim": fn.get("arguments"), "option0": str(options[0])}
    except (json.JSONDecodeError, KeyError, TypeError):
        return None


# ============================================================
# 実行部
# ============================================================

def mark(label):
    print(f"  [MARK {datetime.now():%H:%M:%S}] → {label}")


def show(res, verdict, label):
    pt = res["usage"].get("prompt_tokens", "?")
    head = (res["content"] or "")[:50].replace("\n", " ")
    print(f"    {label}: {verdict} finish={res['finish_reason']} prefill={pt}tok "
          f"({res['elapsed_sec']}s) 「{head}…」")
    if res.get("http_status", 200) >= 400:
        print(f"    ⚠ HTTP {res['http_status']}: {res.get('http_body','')[:80]}")


def canned_step1_history(arm):
    """注入履歴: 出題(canned) + 正答回答(canned)。raw/raw_strip は同一
    (canned assistant は元々 content:null なので剥がしの差は step3 で初めて出る)"""
    if arm in ("raw", "raw_strip", "raw_split"):
        return [
            {"role": "user", "content": normalize(USER_PROMPT_T1)},
            {"role": "assistant", "content": None, "tool_calls": [
                {"id": "call_quiz_1", "type": "function",
                 "function": {"name": "ask_user_question", "arguments": QUIZ_ARGS}},
            ]},
            {"role": "tool", "tool_call_id": "call_quiz_1", "content": RESULT_ASK},
        ]
    # v2: assistant(本文なし)はスキップ、結果はマージ済み user
    return [
        {"role": "user", "content": normalize(USER_PROMPT_T1)},
        tool_result_user_msg([("ask_user_question", QUIZ_ARGS, RESULT_ASK)]),
    ]


def strip_content(msg):
    """①´回避策 (raw_strip): tool_calls付きassistantのcontentを剥がす。
    → 2026-07-12実測で不成立 (3/3で本物の判定スキップ＋捏造連鎖。履歴から自然文
    assistantが消えると「tool callログ文書」ジャンルに固着する・アンカー仮説)。
    バグB再現との対比用に残置"""
    if not msg.get("tool_calls"):
        return msg
    return {"role": "assistant", "content": None, "tool_calls": msg["tool_calls"]}


def split_mixed(msg):
    """①´´回避策 (raw_split): content+tool_calls混在を2連続assistantに分割。
    text-only (会話フレームのアンカー) → tool_calls-only の順。
    assistant連続はFLM+e4bでシロ確定済み (ハーネス[C] n=20) を武器にする。
    混在なし/本文なしのメッセージはそのまま1件で返す"""
    if not msg.get("tool_calls") or not msg.get("content"):
        return [msg]
    return [
        {"role": "assistant", "content": msg["content"]},
        {"role": "assistant", "content": None, "tool_calls": msg["tool_calls"]},
    ]


def append_answer_round(arm, history, prev_res, next_q):
    """Step3 用: 新問題の tool_call ラウンド + options[0] 回答を積む"""
    answer_result = make_answer_result(next_q["option0"])
    if arm == "raw":
        return history + [
            prev_res["raw_message"],  # 生成された assistant を原文のまま (判定文+tool_call 込み) ← バグB再現条件
            {"role": "tool", "tool_call_id": next_q["tc"].get("id") or "call_next",
             "content": answer_result},
        ]
    if arm == "raw_strip":
        return history + [
            strip_content(prev_res["raw_message"]),  # content剥がしecho (①´・不成立確認済み)
            {"role": "tool", "tool_call_id": next_q["tc"].get("id") or "call_next",
             "content": answer_result},
        ]
    if arm == "raw_split":
        return history + split_mixed(prev_res["raw_message"]) + [  # ★分割echo (①´´)
            {"role": "tool", "tool_call_id": next_q["tc"].get("id") or "call_next",
             "content": answer_result},
        ]
    # v2: assistant は本文があれば本文のみ (tool_calls は送らない)、空ならスキップ
    out = list(history)
    if prev_res["content"]:
        out.append({"role": "assistant", "content": prev_res["content"]})
    out.append(tool_result_user_msg(
        [("ask_user_question", next_q["args_verbatim"], answer_result)]))
    return out


def run_chain(arm, chain_no, use_stream, log):
    print(f"  ── {arm} chain {chain_no} ──")
    hist = canned_step1_history(arm)

    # Step1 ★主計測
    mark(f"{arm} c{chain_no} step1")
    r1 = call_flm(build_messages(hist), use_stream=use_stream)
    v1 = judge_step1(r1)
    show(r1, v1, "step1")
    log({"arm": arm, "chain": chain_no, "step": 1, "verdict": v1,
         "sent_messages": build_messages(hist), **r1})
    outcome = {"step1": v1, "step2": None, "step3": None}
    if v1 in ("SKIP", "REPEAT", "GRAY"):
        return outcome  # 打ち切り (記録済み)

    # Step2 (JUDGE のみ。JUDGE_AND_NEXT は次問が既に出ているのでスキップ)
    if v1 == "JUDGE":
        hist = hist + [
            {"role": "assistant", "content": r1["content"]},
            {"role": "user", "content": normalize(USER_PROMPT_NEXT)},
        ]
        mark(f"{arm} c{chain_no} step2")
        r2 = call_flm(build_messages(hist), use_stream=use_stream)
        v2v = judge_step2(r2)
        show(r2, v2v, "step2")
        log({"arm": arm, "chain": chain_no, "step": 2, "verdict": v2v,
             "sent_messages": build_messages(hist), **r2})
        outcome["step2"] = v2v
        if v2v != "FIRE":
            return outcome
        next_src = r2
    else:  # JUDGE_AND_NEXT
        next_src = r1

    # Step3: 新問題に options[0] で機械回答 → 判定行動が出るか
    next_q = parse_next_question(next_src)
    if next_q is None:
        print("    step3: ARGS_MALFORMED (新問題の args 解析不能 → 打ち切り)")
        log({"arm": arm, "chain": chain_no, "step": 3, "verdict": "ARGS_MALFORMED",
             "raw_args": (next_src["tool_calls"][0].get("function") or {}).get("arguments")
             if next_src["tool_calls"] else None})
        outcome["step3"] = "ARGS_MALFORMED"
        return outcome

    hist = append_answer_round(arm, hist, next_src, next_q)
    mark(f"{arm} c{chain_no} step3 (回答: {next_q['option0'][:20]})")
    r3 = call_flm(build_messages(hist), use_stream=use_stream)
    v3 = judge_step3(r3)
    show(r3, v3, "step3")
    log({"arm": arm, "chain": chain_no, "step": 3, "verdict": v3,
         "answered_option": next_q["option0"],
         "sent_messages": build_messages(hist), **r3})
    outcome["step3"] = v3
    return outcome


def run(only=None, n_chains=N_CHAINS, use_stream=False):
    log_path = Path(f"raw_quiz_results_{datetime.now():%Y%m%d_%H%M%S}.jsonl")
    logf = log_path.open("w", encoding="utf-8")

    def log(record):
        logf.write(json.dumps(record, ensure_ascii=False) + "\n")
        logf.flush()

    print(f"=== 生形式クイズフロー・プローブ (FLM {BASE_URL}, model={MODEL}, 各 {n_chains} チェーン) ===")
    print("※ FLM コンソールを並べて表示 (Matched X out of Y rounds 行の突合用)")

    arms = [a for a in ("raw", "raw_strip", "raw_split", "v2") if only is None or a == only]
    all_outcomes = {}
    for arm in arms:
        print(f"\n=== Arm-{arm} ===")
        all_outcomes[arm] = [run_chain(arm, i + 1, use_stream, log)
                             for i in range(n_chains)]

    logf.close()
    print("\n" + "=" * 50)
    print("📊 サマリ")
    for arm, outs in all_outcomes.items():
        s1 = {}
        for o in outs:
            s1[o["step1"]] = s1.get(o["step1"], 0) + 1
        s2 = [o["step2"] for o in outs if o["step2"]]
        s3 = [o["step3"] for o in outs if o["step3"]]
        print(f"  Arm-{arm}:")
        print(f"    Step1: " + " / ".join(f"{k} {v}" for k, v in sorted(s1.items())))
        skip = s1.get("SKIP", 0)
        print(f"    ★SKIP率: {skip}/{n_chains}")
        if s2:
            c2 = {v: s2.count(v) for v in set(s2)}
            print(f"    Step2: " + " / ".join(f"{k} {v}" for k, v in sorted(c2.items())))
        if s3:
            c3 = {v: s3.count(v) for v in set(s3)}
            print(f"    Step3: " + " / ".join(f"{k} {v}" for k, v in sorted(c3.items())))
    print(f"\n生ログ: {log_path} (送信メッセージ全文込み。GRAY系は目視)")
    print("""
🔍 読み方:
  【①´検証 (--stream --only raw_strip)】
  raw_strip step3 = JUDGE3   → バグB回避策成立 = v3実装要件確定
                               (stream受信 + content剥がし再送)
  raw_strip step3 = GRAY3等  → 剥がしでも不十分。生ログのcontentとFLMログの
                               RAW Outputを突合して失敗形を特定すること
  raw_strip の step3 prefill  → 剥がしecho≠本文入りcheckpointのため
                               当該ラウンド税1回 (全量) は想定内 (推定)
  【v1.0からの基準】
  ★SKIP率が raw ≦ v2       → 悪化なし = 残ピース①クローズ
  JUDGE_AND_NEXT             → 理想挙動 (判定+次問)。SKIP と混同しないこと
  Step2 TEXT_QUIZ            → 本文出題 = 発火閾値の中段抜け (実機報告と同型)
  ※ stream:false の raw は バグA により content が欠落し SKIP に誤判定される
    (v1.0実測済み)。挙動測定は --stream 推奨
""")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", choices=["raw", "raw_strip", "raw_split", "v2"], default=None)
    ap.add_argument("-n", type=int, default=N_CHAINS, help="各アームのチェーン数")
    ap.add_argument("--stream", action="store_true",
                    help="SSE 送信 (tool_calls はデルタ集約で復元)")
    args = ap.parse_args()
    run(only=args.only, n_chains=args.n, use_stream=args.stream)
