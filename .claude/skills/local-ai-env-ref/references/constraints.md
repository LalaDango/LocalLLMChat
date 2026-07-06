# ハードウェア・ソフトウェア制約条件（LocalLLMChat向け縮約版）

最終更新: 2026-07-07
検証環境: Lenovo IdeaPad 5 2-in-1 Gen 10 ＋ FastFlowLM v0.9.43

> **経路の注意**: FLM（:52625）へのアクセス経路は2系統ある。
> ① SM経由（自作Session Manager :8800、PC上のメモ蓄積用。LocalLLMChatとは無関係）
> ② **FLM直結**（LocalLLMChat・Open WebUI・Vane等）。
> 「SMが実装済み/対策済み」とされる保護（U+3000正規化・容量超過ゲート等）は②の経路では効かない。
> ハードウェア制約・FLM本体の挙動は両経路共通。

## ハードウェア仕様

| 項目 | 値 |
|------|------|
| PC | Lenovo IdeaPad 5 2-in-1 Gen 10 |
| CPU | AMD Ryzen AI 5 340 |
| RAM | 16GB |
| NPU | AMD XDNA (AIEアーキテクチャ) |
| 共有メモリ | **7.6GB** (システムRAMの約50%が上限) |
| GPU | 統合GPU (専用VRAM無し) |
| スマートフォン | Galaxy S26（LocalLLMChat の実行端末） |

## NPUメモリ制約

### 絶対的な上限: 7.6GB

- システムRAM 16GBのうち、NPU共有メモリとして使える上限は約50% = 7.6GB
- この7.6GBにモデルウェイト＋KVキャッシュ＋ワーキングメモリ全てが収まる必要がある
- **この制約はハードウェア的なもので、ソフトウェアでは回避不可**

## モデルサイズ上限（FastFlowLM経由・NPU実行）

| パラメータ数 | 量子化 | 推定サイズ | 動作可否 |
|-------------|--------|-----------|---------|
| ≤4B | Q4系 | ~2.5-3GB | ✅ 余裕あり |
| Gemma 4 E4B (MatFormer) | NPU形式 | - | ✅ **現主力**。ctx-len 32768で運用 |
| 8B | Q4_1 | ~5GB | ✅ コンテキスト制限あり |
| 9B (マルチモーダル) | Q4系 | ~5.5-6GB+ | ❌ Vision込みだと7.6GB超 |
| 9B (テキスト専用改造) | Q4系 | ~5-5.5GB | ✅ ctx-len 16384で運用可 |
| 12B+ | 任意 | >7GB | ❌ 動作不可 |

- FastFlowLMはNPU最適化済みの独自フォーマット。GGUFは直接使用不可
- Ollama等のCPU実行は動くが遅い（9Bで約5tok/s）

## prefill制約（v0.9.43で大幅緩和）

- `--prefill-chunk-len`（既定4096）により長文prefillは自動チャンク分割される
- **旧「9Bは1回1,792トークンで即死」「4Bは~8Ktok上限」は v0.9.43 で消滅**（3,931tok単一チャンク完走を実証、2026-06-11）
- 全量prefillのコスト目安: 30K級で約60秒（e4bが458tps @90-100%帯）
- 2026-04以前のログ・メモの prefill 上限記述は現在無効

## コンテキスト長と占有率の実測挙動（gemma4-it:e4b、ctx-len 32768）

- decode速度は占有率に**単調比例で低下、崖はない**: 7.40tps(0-10%帯) → 5.34tps(90-100%帯)
- 全量prefill速度も90-100%帯で458tpsと崖なし
- 天井到達時: 生成が途中停止（`Max length reached`）。Serveは生存
- **位置参照の分解能限界（2026-07-04実証／2026-07-06更新）— 2つの別現象を区別すること**:
  - **会話履歴の件数カウント**（「さっきの」「直近3件」等）: 高占有帯（72%以上で実証）で
    取り違える。内容・タイトルで名指しすれば正解。「忘れる」のではなく「数えられない」
  - **単一添付文書内の位置参照**: **16K tok（60KB級・占有49%）まで無傷**（8/8満点、2026-07-06）。
    先頭・末尾・横断・途中切れ検出・一字一句引用まで正確。ネガティブコントロールでも創作なし
  - 劣化開始帯は占有49%〜72%の間のどこか（**未検証**）。「e4bは位置参照が弱い」と
    一括りにしないこと — 弱いのは履歴の件数カウントのみ
- 日本語はUTF-8で3バイト/文字 → 英語比でトークン効率が悪い。同じKBでもトークン数が多くなる

## LocalLLMChat添付ファイル上限のKB境界（2026-07-06）

- アプリ側上限は **KiB換算（1KB=1024B）**、Android標準ファイルマネージャー表示は **1KB=1000B換算**
  → 「59.97KB」表示の実体は約58.6KiB。設定58で切り詰め・60で通過は仕様どおり
- **60KB設定＝16K tokで位置参照満点を実証** → 旧28KBからの引き上げに品質面のブロッカーなし。
  残る制約は TTFT約30秒（全量prefill時）のUXのみ。60KB超〜の品質は未検証
- 60KB ≒ 16K tok（日本語混在ログ実測: 59.97KB → 16,000tok。約3.7B/tok）

## キャッシュ／checkpointの制約【最重要・FLM本体の性質＝LocalLLMChatにも適用】

### シングルキャッシュスロット＋checkpoint照合規則（v0.9.43確定）

- FLMはキャッシュスロットを1つだけ保持。照合はテンプレ適用後の**トークン単位diff**
- checkpoint復元が効く条件: 「**最後のuser発言を除いた会話履歴全体がcheckpointと完全一致**」
  すること。つまり1リクエストにつきuser発言が1個だけ増える普通の会話が最も効率的
- 照合は最新checkpointのみ。不一致なら過去checkpointへのフォールバックなしで**全量prefill直行**
- `stream:false` でもキャッシュ有効（旧「stream:true必須」は撤廃。2026-06-11実証）
- キャッシュヒット判定はログではなく実測値で。**前ターンの `active_kv_tokens` との差分**で判定する:
  `余剰 = prompt_tokens + completion_tokens + 前ターンactive_kv_tokens − 今回active_kv_tokens`
  **余剰 ≈ 0 ならヒット、余剰 ≈ 前ターンactive_kv_tokens ならミス（全量prefill）**
  （ヒット時の `usage.prompt_tokens` は「新規prefill分のみ」になる点に注意）
  - 旧式「`active_kv ≫ prompt+completion` ならヒット」は履歴が小さいと誤判定する
    （履歴24tokの2ターン目で長い応答が出ると、ヒットでも prompt+completion ≈ active_kv になる。2026-07-06実機実証）
  - LocalLLMChat の実装値: 余剰 ≥ 前ターンKV×0.5 かつ 前ターンKV ≥ 100 でミス判定（ヒット・ミス両方向を実機検証済み）

### 画像とキャッシュ（2026-07-06 LocalLLMChat改修時に実測）

- 画像は解像度によらず**固定 ~256 tok** に正規化される（2048px化しても入力情報量は増えない）
- 履歴の画像を毎ターン再送しても、キャッシュヒット時は FLM が**ペイロード段階で破棄**する
  （ログ: `Prompt-cache hit: dropped N cached image(s) from payload`。エンコーダ再実行なし）
  → 履歴画像の再送コストは転送（Tailscale 数百KB）のみ。checkpoint一致のため再送が正解
- 逆に履歴画像をプレースホルダ文字列に置き換えて送ると checkpoint 不一致 → 毎ターン全量prefill

### キャッシュミスの主因（FLM本体の性質。対策はSM側にしか実装されていない＝直結クライアントは未対策）

- **U+3000（全角スペース）**が履歴に混ざるとミスを誘発（半角正規化で 5/6→0/6 に改善した実績）
- 履歴のassistant内容が生成実物と1トークンでも違うと次の1リクエストは全量prefill
- 複数メッセージをまとめて追加すると復元されず全量prefill（1ターン1発言が原則）
- モデル切替・Serve再起動・別セッション切替でキャッシュ破壊。**運用中のmodel名変更は厳禁**
  （キャッシュ全滅＋約46秒のモデルロード）

### 容量超過の危険（実証済み 2026-07-05）

- **容量超過のprefill強行はcheckpoint全滅＝セッション構造的死亡**
  （prefill途中停止→checkpoint 0リセット、復旧手段なし）
- SMには送信前ゲートがあるが、**FLM直結クライアント（LocalLLMChat等）からの容量超過prefillは無防備**

### 再起動とキャッシュの生存

- FLM serve再起動は全量prefill確定（30Kで約60秒）
- キャッシュはServe起動中なら数日単位で生存（PCスリープ復帰後も維持）

## KV実測フィールド（v0.9.41〜）

chat completionsの`usage`内で取得可能:

- 非stream: `kv_token_occupancy_rate_percentage` のみ
- streamの最終チャンク: `active_kv_tokens`・`max_kv_token_capacity` 付き、さらに生TTFT・prefill/decode速度
- 実フィールド名は「rate」入り（リリースノート表記と微差）
- FLM本体の機能なのでどの経路でも読める（LocalLLMChatでも利用可能）

## ネットワーク構成

```
[Galaxy S26] ─Tailscale→ [Lenovo IdeaPad 5]
  LocalLLMChat ──────────→ FastFlowLM (localhost:52625/v1) ← OpenAI互換API
```

- Tailscale経由でスマホからPC側FLMにリモートアクセス
- FLM標準起動コマンド（e4b主軸・実運用フルオプション版）:
  ```
  flm serve gemma4-it:e4b --pmode turbo --ctx-len 32768 --port 52625 --host 0.0.0.0 --socket 40 --q-len 40 --asr 0 --embed 0 --cors 1 --preemption 0 --prefill-chunk-len 4096
  ```
- `/v1/models`はカタログ全体を返す（ロード中モデルの検出には使えない）
