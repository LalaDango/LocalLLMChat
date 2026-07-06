---
name: local-ai-env-ref
description: ユーザーのローカルAI環境（ハードウェア、FastFlowLMサーバー、制約条件）のリファレンス。LocalLLMChat（Androidアプリ）の開発・改修時、新しいモデルがこの環境で動作するか判定する時、FastFlowLM関連の質問、コンテキスト長・メモリ・NPU制約・KVキャッシュ・checkpointに関する議論で必ず参照すること。「うちの環境で動く？」「このモデル使える？」「メモリ足りる？」といった質問には必ずこのスキルを使う。ローカルLLM、NPU、FastFlowLM、Qwen、Gemma、量子化、KVキャッシュ、コンテキスト長に関する話題全般でトリガーすること。
---

# ローカルAI環境リファレンス（LocalLLMChat向けスリム版）

ユーザー固有のハードウェア・FastFlowLMサーバー環境と、検証済みの制約条件をまとめたスキル。
新しいモデルや技術の実用性を評価する際の判定基準として使用する。
※ LocalLLMChat（FLM直結クライアント）に関係する内容へ絞った縮約版。Claude.ai側フル版
（SM含む環境全体の正本）とは**別ディストリビューションでファイル一致は目指さない**。
新知見は双方向に「内容」を選別還流する（ファイル丸ごとコピーで上書きしない）。

## クイックリファレンス：動作判定チェックリスト

新しいモデル/技術について「動く？」と聞かれたら、以下を順にチェック：

1. **モデルサイズ**: Q4量子化後のサイズが7.6GB未満か？ → 超えるなら即NG
2. **アーキテクチャ**: FastFlowLMが対応しているか？ → 未対応なら待ち
3. **コンテキスト長**: モデルサイズ＋KVキャッシュが7.6GB内に収まるか？
4. **prefill**: v0.9.43のchunk prefill（既定4096）により1回入力の旧上限（9Bの1,792tok等）は撤廃済み。ただし総KV容量は別問題
5. **フォーマット**: NPU最適化済みフォーマットか？ → GGUF等は直接使えない
6. **ランタイム**: FastFlowLM以外のランタイム（llama.cpp等）はCPU実行のみ

詳細な制約と根拠は `references/constraints.md` を参照。

## 現在の主軸構成（2026-07-05時点）

- **推論**: FastFlowLM v0.9.43（NPU、port 52625）＋ **gemma4-it:e4b**（ctx-len 32768、--pmode turbo）
- **旧主力 Qwen3.5:4b は退役**（qwen3-it:4b は検索AI Vane 用に現役）
- LocalLLMChat は Tailscale 経由で FLM（:52625）に**直結**する
- ※ PC上には自作の FLM Session Manager（SM、localhost:8800）という別経路のミドルウェアも存在するが、
  **LocalLLMChat とは無関係**。SMが実装している保護（U+3000正規化・容量ゲート等）は
  FLM直結クライアントには効かない点にだけ注意（FLM本体の挙動・制約は両経路共通）

## ファイル構成

- **references/constraints.md** — ハードウェア・FLMサーバーの詳細制約（数値・検証日付付き）
- **references/verified-models.md** — 検証済みモデル一覧と実測値、新モデル評価テンプレート
- **references/known-issues.md** — 既知の問題・ワークアラウンド（キャッシュミス要因、e4bの挙動特性、gemma4ツール連携の変換レシピ）

各リファレンスは必要に応じて読み込むこと。
判定に迷う場合は `constraints.md` と `verified-models.md` の両方を確認せよ。

## 旧知見の無効化（2026-03頃の記憶を読む際の注意）

| 旧（2026-03頃） | 現在（2026-07-05） |
|---|---|
| 主力モデル: Qwen3.5:4b（ctx 32768） | **gemma4-it:e4b**（MatFormer、ctx-len 32768） |
| 9Bはprefill 1回1,792tokで即死／4Bも~8Ktok上限 | **撤廃**。FLM v0.9.43のchunk prefill（--prefill-chunk-len 既定4096）で長文は自動分割 |
| FastFlowLM v0.9.39以前 | **v0.9.43**。stream:falseでもキャッシュ有効、KV実測フィールドあり。reasoning_effort は v0.9.39〜（**qwen3/qwen3.5系限定**、none/low/medium/high。Gemma系は無視）。Gemma 4 の thinking は別方式（**プロンプト（質問）冒頭**の `<\|think\|>` トークンでON/OFF、段階指定なし。質問冒頭に付けるだけで発火することを実機確認 2026-07-06） |
| checkpoint挙動は未整理 | e4b=**生成後checkpoint保持型**（次ターンは新規userトークンのみprefill）／9B=非保持型 |
| モバイル: Gemini Nano V3のみ | NanoChat（別アプリ）はNano 4対応済み。LocalLLMChatには影響なし |

## 更新履歴

- 2026-07-07: Claude.ai側スキル2026-07-07版から同期。①gemma4のrole:"tool"無視の真因
  （FLMテンプレートがtoolロールを落とす）とv2変換レシピ（commit c1250af・known-issues.md収録）
  ②ツール発火は閾値（ツール名明示100%/なし0%）③ツール会話は毎ターン全量prefillの宿命
  ④添付文書の位置参照16K/49%で8/8満点・履歴件数カウントとの別現象区別 ⑤添付上限のKiB/KB境界
  ⑥e4b vision確定（画像1枚≒256tok固定・1024px/q85で十分・読解限界12〜14px・幻覚コード注意）
  ⑦thinking制御の訂正（system prompt先頭→プロンプト冒頭）
- 2026-07-05: LocalLLMChat プロジェクト内（.claude/skills/）に配置。ユーザー指示により LocalLLMChat 関連内容へ縮約（SM運用・Vane・Open WebUI・モバイル他アプリ・開発環境の罠は削除。原本は Cowork 側管理）
- 原本履歴: 2026-03-28 初版 → 2026-04-16 v0.9.39対応 → 2026-07-05 全面改訂（v0.9.43知見・e4b移行）
