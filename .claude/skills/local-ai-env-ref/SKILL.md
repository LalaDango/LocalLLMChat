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

1. **NPU共有メモリ上限**: 現在 **60% = 約9.1GB**（Windowsのソフト制限。既定50%=7.6GB）。
   判定は**ファイルサイズではなく実測NPUピーク**で行う（12Bはファイル9.32GB→ピーク7.2〜8.3GB）。
   未実測モデルは同系統の実測から見積もり、「要実測」と明記する
2. **物理RAM（実質の最終制約）**: 15.2GB使用可。12BでFLMプロセス8〜9.7GB。
   **ロード時に空きRAMが0付近まで落ちる**のが最大の危険点 → 起動前の空き9GB以上が条件
3. **Windows AIの割り込み**: `WorkloadsSessionHost` がNPUを2〜3GB掴むと合算で上限超過→TDR→FLM停止。起動前に不在確認
4. **アーキテクチャ**: FastFlowLMが対応しているか？ → 未対応なら待ち
5. **prefill**: chunk prefill（v0.9.43〜）により1回入力の旧上限（9Bの1,792tok等）は撤廃済み。運用は `--prefill-chunk-len` 4096/2048 を明示指定。ただし総KV容量は別問題
6. **フォーマット**: NPU最適化済みフォーマットか？ → GGUF等は直接使えない
7. **ランタイム**: FastFlowLM以外のランタイム（llama.cpp等）はCPU実行のみ

詳細な制約と根拠は `references/constraints.md`、Windows AI/TDRは `references/known-issues.md` を参照。

## 現在の主軸構成（2026-10-06時点）

- **推論**: FastFlowLM（NPU、port 52625）＋ 主力 **gemma4-it:e4b**（ctx-len 32768、--pmode turbo）、
  大型枠 **gemma4-it:12b**（ctx 16384、decode 4.5〜5.0tps、checkpoint非保持型）
- **FLMバージョン**: 実機は **v1.0.7**（12bは v1.0.4 以上が必須）。0.9.45以降は資料への反映を止めていたため、
  本スキルの数値は「ツール連携系=v0.9.45（2026-07-11〜13）」「NPU上限・12b・トークン見積り=v1.0.7（2026-10）」
  「それ以外=v0.9.43」時点の実測。**v0.9.45のツール2バグがv1.0.7で残っているかは未再検証**。
  v1.0.7は上流の最新リリース（2026-10-06確認）。v1.0.0からリポジトリはROCm organization配下に移転
- **v1.0.7 の usage 変更（PR #729）**: `prompt_tokens` がキャッシュ分を含む全量報告になり、checkpoint復元分は
  `prompt_tokens_details.cached_tokens` に分離（v0.9.xは差分報告）。新規prefill分＝prompt_tokens − cached_tokens。
  LocalLLMChatのfull prefill判定は `cachedTokens` 優先・旧余剰式はフォールバック
- **NPU上限**: 60%（9.1GB、2026-10-03〜）。70%はSDで不安定のため不採用
- **起動**: `%USERPROFILE%\AI_Workspace\tools\start_flm.ps1`（Windows AI不在と空きRAM 9GBをチェックしてから
  `flm serve`。既定 12b/ctx 16384/turbo/52625/chunk 2048、`-Model` `-Ctx` で変更）
- **検証時のみ**: 同フォルダの `mem_monitor.ps1 -ProcessName flm`（FLM単体とWindows AIのNPU使用量を分けてCSV化。
  共有時は末尾の要約数行で十分。アダプタLUIDは再起動で変わる）。PS 5.1用スクリプトはUTF-8 **BOM付き**で保存
- **旧主力 Qwen3.5:4b は退役**（qwen3-it:4b は検索AI Vane 用に現役）
- LocalLLMChat は Tailscale 経由で FLM（:52625）に**直結**する
- ※ PC上には自作の FLM Session Manager（SM、localhost:8800）という別経路のミドルウェアも存在するが、
  **LocalLLMChat とは無関係**。SMが実装している保護（U+3000正規化・容量ゲート等）は
  FLM直結クライアントには効かない点にだけ注意（FLM本体の挙動・制約は両経路共通）

## トークン数見積り（送信前ガード用・FLM同梱tokenizerで実測した安全側係数）

- ASCII 0.45 / 64文字以上連続するbase64系英数字 0.8 / 非ASCII（日本語等）0.9 トークン/UTF-16単位
- 実測比（見積り÷実際）: HTML 1.07〜1.2、圧縮JS 1.05〜1.4、base64 1.09〜1.15、日本語md 1.2〜1.35、
  日本語会話文 1.6〜1.9、コード 1.9〜2.0。Gemma4 12B/E4Bは同一トークナイザー、Qwen3.5-9Bも全て安全側
- 一律0.9はHTMLで約2倍の過大、一律0.4はbase64で約半分の過小（溢れの原因）。実装は `util/TokenEstimator.kt`
  （LocalLLMChatの容量ガード: max_tokens を残り容量へ自動切り詰め・1通目は全会話の最終KV容量で判定・「それでも送る」あり）

## ファイル構成

- **references/constraints.md** — ハードウェア・FLMサーバーの詳細制約（数値・検証日付付き）
- **references/verified-models.md** — 検証済みモデル一覧と実測値、新モデル評価テンプレート
- **references/known-issues.md** — 既知の問題・ワークアラウンド（キャッシュミス要因、e4bの挙動特性、
  gemma4ツール連携: v0.9.45の2バグとv3要件（実装済み）・v2変換レシピ（フォールバック残置）・
  野生捏造シグネチャとstrip・キャッシュ税の訂正・崩壊頻度・地雷リスト）

各リファレンスは必要に応じて読み込むこと。
判定に迷う場合は `constraints.md` と `verified-models.md` の両方を確認せよ。

## 旧知見の無効化（過去の記憶・メモを読む際の注意）

| 旧 | 現在 |
|---|---|
| NPU上限7.6GBはハードウェア由来で回避不可 | **誤り**（2026-10-03）。Windowsのソフト制限（レジストリ `SystemPartitionCommitLimitPercentage`）。現在60%=9.1GB |
| 12B+は動作不可／Qwen3.5-9B公式マルチモーダル版は動作不可 | 60%で**gemma4-it:12b**（ctx 4K/8K/16K）・**9B公式版**（ctx 16384）とも動作（2026-10-03〜06）。条件はWindows AI不在＋空きRAM 9GB |
| 判定は「モデル＋KVが7.6GB以内か」 | **実測NPUピーク＋物理RAM**で判定。ファイルサイズ・ctx-lenはNPUピークにほぼ比例しない（ctx-lenはRAMに効く） |
| 部分ヒット時の prompt_tokens は差分報告（余剰式で判定） | v0.9.xのみ。**v1.0.7〜は全量報告＋cached_tokens**（PR #729・2026-10-04実機確認） |
| ツール会話は毎ターン全量prefillの宿命（2026-07-07記録） | **誤り**（2026-07-12訂正）。生形式は部分ヒット成立・v2変換の税は「ツールラウンドごとに1回」（known-issues.md参照） |
| gemma4はrole:"tool"がモデルに届かない → v2変換で対処（2026-07-07） | **v0.9.45で素形式読解5/5**＝v2の存在理由消滅。生形式ベース「**v3へ移行済み**」（2026-07-13・v2はフォールバック残置） |
| 主力モデル: Qwen3.5:4b（ctx 32768） | **gemma4-it:e4b**（MatFormer、ctx-len 32768） |
| 9Bはprefill 1回1,792tokで即死／4Bも~8Ktok上限 | **撤廃**。FLM v0.9.43のchunk prefill（--prefill-chunk-len 既定4096）で長文は自動分割 |
| FastFlowLM v0.9.39以前 | **v0.9.45**（以下の機能検証はv0.9.43時点）。stream:falseでもキャッシュ有効、KV実測フィールドあり。reasoning_effort は v0.9.39〜（**qwen3/qwen3.5系限定**、none/low/medium/high。Gemma系は無視）。Gemma 4 の thinking は別方式（**プロンプト（質問）冒頭**の `<\|think\|>` トークンでON/OFF、段階指定なし。質問冒頭に付けるだけで発火することを実機確認 2026-07-06） |
| checkpoint挙動は未整理 | e4b=**生成後checkpoint保持型**（次ターンは新規userトークンのみprefill）／9B=非保持型 |
| モバイル: Gemini Nano V3のみ | NanoChat（別アプリ）はNano 4対応済み。LocalLLMChatには影響なし |

## 更新履歴

- 2026-10-06: フル版 2026-10-06 改訂から選別還流＋LocalLLMChat側の実測を反映。①NPU上限はWindowsのソフト制限
  （60%=9.1GBで運用）②gemma4-it:12b・9B公式版が動作 ③判定基準を実測NPUピーク＋物理RAMへ ④Windows AI
  （WorkloadsSessionHost）のNPU割り込み→TDR→FLM停止と start_flm.ps1 ⑤トークン見積り係数（TokenEstimator.kt）
  ⑥FLM v1.0.7 の usage 変更（cached_tokens）とアプリの容量ガード。SD・SM・モバイル由来の内容は取り込まず。
  同日追補: フル版追補（リリースノート確認）から usage 変更が v1.0.7・PR #729 由来であること、
  v1.0.0 での ROCm organization 移転、chunk-len 既定値の v1.0.x 未確認を反映
- 2026-07-13: gemma4ツール連携の**v3実装・検証グリーン**を反映（ソースはknown-issues.md 07-13版）。
  ①v3（生形式＋分割echo＋孤児/宙ぶらりんガード＋捏造断片strip＋max_tokens 2048キャップ）が
  LocalLLMChatの現行実装に・v2はフォールバック残置 ②ハーネスv1.2/実機とも全項目グリーン、
  残タスク「生形式エラー系」回収（C5実質10/10 REPORT・RETRY 0）③strip実戦2勝
  （実物3シグネチャ: response:ツール名{ / <|"|> / <tool_call|>。ユニットテスト固定）
  ④捏造tool_callはFLMが本物としてパースし実行系に流れる（stripは本文断片のみ担当）
- 2026-07-12: フル版還流ノート（docs/gemma4-tool-inflow-note-2026-07-12.md、ソースはフル版
  tool-mode-collapse.md / known-issues.md 2026-07-12版）から選別還流。①FLM v0.9.45で
  role:"tool"素形式が読解可能に→v2変換は退役方向・v3要件5点（stream受信・分割echo・孤児tool禁止・
  捏造断片strip・ツール名焼き込み）②v0.9.45の2バグ（stream:false本文欠落／混在echo再展開破損）
  ③キャッシュ税の訂正（「毎ターン全量prefill」は誤り→生形式は部分ヒット・v2はラウンドごと1回）
  ④崩壊は稀な初手事故（q4nx 40/40 REPORT）・prefill完治策は使用不可⑤地雷リスト5項目
  ⑥e4b評価の訂正（判定スキップ約1/5はバグA由来・モデル無実）
- 2026-07-07: Claude.ai側スキル2026-07-07版から同期。①gemma4のrole:"tool"無視の真因
  （FLMテンプレートがtoolロールを落とす）とv2変換レシピ（commit c1250af・known-issues.md収録）
  ②ツール発火は閾値（ツール名明示100%/なし0%）③ツール会話は毎ターン全量prefillの宿命（→2026-07-12訂正）
  ④添付文書の位置参照16K/49%で8/8満点・履歴件数カウントとの別現象区別 ⑤添付上限のKiB/KB境界
  ⑥e4b vision確定（画像1枚≒256tok固定・1024px/q85で十分・読解限界12〜14px・幻覚コード注意）
  ⑦thinking制御の訂正（system prompt先頭→プロンプト冒頭）
- 2026-07-05: LocalLLMChat プロジェクト内（.claude/skills/）に配置。ユーザー指示により LocalLLMChat 関連内容へ縮約（SM運用・Vane・Open WebUI・モバイル他アプリ・開発環境の罠は削除。原本は Cowork 側管理）
- 原本履歴: 2026-03-28 初版 → 2026-04-16 v0.9.39対応 → 2026-07-05 全面改訂（v0.9.43知見・e4b移行）
