# ハードウェア・ソフトウェア制約条件（LocalLLMChat向け縮約版）

最終更新: 2026-10-06（NPUメモリ制約・モデルサイズ上限を改訂）
検証環境: Lenovo IdeaPad 5 2-in-1 Gen 10 ＋ FastFlowLM（実機は v1.0.7）
※ NPU上限・12b・v1.0.x usageの実測は v1.0.7（2026-10）。ツール連携系は v0.9.45（2026-07-11〜12）。それ以外は v0.9.43 時点

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
| 共有メモリ | **9.1GB**（60%設定・2026-10-03〜。既定は50%=7.6GB。レジストリで変更可） |
| GPU | 統合GPU (専用VRAM無し) |
| スマートフォン | Galaxy S26（LocalLLMChat の実行端末） |

## NPUメモリ制約

### 上限の正体: Windowsのソフト制限（2026-10改訂。旧「ハードウェア由来・回避不可」は誤り）

- レジストリ `HKLM\SYSTEM\CurrentControlSet\Control\GraphicsDrivers\MemoryManager` の DWORD
  `SystemPartitionCommitLimitPercentage`（範囲50〜100・再起動で反映）。既定50%＝15.2GB×50%＝7.6GB
- **現在60%（約9.1GB）で運用**。タスクマネージャーのNPU共有メモリ最大値で反映を確認できる。戻す時は値を削除して再起動
- 70%（10.6GB）は別用途（SDXL）でページイン失敗が出たため不採用。**60%超は非推奨**
- 上限は**アダプタ合計**。FLM以外（Windows AI＝WorkloadsSessionHost）の使用分も合算される（known-issues.md参照）
- NPU共有メモリの実体はメインRAM。上限％を上げても物理RAM（15.2GB）は増えない

### 実質の最終制約: 物理RAM

- 12BではFLMプロセスが8〜9.7GB（ctx 16384で約+1.5GB）。生成中の空きRAMは0.35〜2.2GB
- **ロード時に空きRAMが0付近まで落ち、ページングが毎秒10万超になる**のが最大の危険点。
  起動前の空きRAMが4.4〜5.8GBの回は大荒れ、9.8〜11.4GBの回は安定 → **起動前9GB以上**を基準にする
  （`AI_Workspace\tools\start_flm.ps1` が自動チェック）

### NPUピークの性質（2026-10実測・FLM v1.0.7）

- **ファイルサイズ＝NPU使用量ではない**: 12B（ファイル9.32GB）でピーク7.2〜8.3GB、9B公式版（8.66GB）で6.68GB
- 待機時0.6〜1.6GB、生成中は2〜8GBで激しく上下する（重みを出し入れしている挙動に見える＝推定）
- **ctx-lenでほとんど変わらない**（12Bで4K/8K/16Kとも7.2〜8.3GB。ただしKVを16K近くまで埋めた計測は未実施）。ctx-lenはNPUよりRAMに効く
- `--prefill-chunk-len` 2048と4096でピーク差なし

## モデルサイズ上限（FastFlowLM経由・NPU実行）

| パラメータ数 | 量子化 | 推定サイズ | 動作可否 |
|-------------|--------|-----------|---------|
| ≤4B | Q4系 | ~2.5-3GB | ✅ 余裕あり |
| Gemma 4 E4B (MatFormer) | NPU形式 | - | ✅ **現主力**。ctx-len 32768で運用 |
| 8B | Q4_1 | ~5GB | ✅ コンテキスト制限あり |
| 9B (公式マルチモーダル) | NPU形式 | 8.66GB（実測ピーク6.68GB） | ✅ 60%でctx 16384動作（2026-10-03。旧「7.6GB超で不可」は失効） |
| 9B (テキスト専用改造) | Q4系 | ~5-5.5GB | ✅ ctx-len 16384で運用可 |
| gemma4-it:12b | NPU形式（Q4_0） | 9.32GB（実測ピーク7.2〜8.3GB） | ✅ 60%でctx 4K/8K/16K完走（2026-10-04〜06）。**条件: Windows AI不在＋起動前空きRAM 9GB以上** |
| 12B超 | 任意 | - | ⏳ 要実測（RAM 15.2GBが先に尽きる可能性大） |

- FastFlowLMはNPU最適化済みの独自フォーマット。GGUFは直接使用不可
- Ollama等のCPU実行は動くが遅い（9Bで約5tok/s）

## prefill制約（v0.9.43で大幅緩和）

- `--prefill-chunk-len` により長文prefillは自動チャンク分割される（既定値はv0.9.43で4096・v0.9.45のhelp表記は-1・
  v1.0.xは未確認。**運用では4096または2048を明示指定**）
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
- **v0.9.45補記: ラウンド単位の部分ヒットが成立するケースを実測（2026-07-12・確定）**:
  ツール生形式の履歴で `Matched 2 out of 4 rounds` → checkpoint復元＋差分のみprefill を観測。
  成立条件は「checkpointのラウンド列が送信履歴のプレフィックス」（推定・3観測の統一説明）。
  部分ヒット時の `prompt_tokens` は**差分トークン数**を報告するため、絶対値でのミス判定は不可
  （余剰式判定は従来どおり有効）。詳細は known-issues.md「ツール会話のキャッシュ税」
- **v1.0.x補記（v1.0.7〜・PR #729。2026-10-04実機確認）**: `prompt_tokens` は**全量**を報告し、checkpointから復元した分は
  `prompt_tokens_details.cached_tokens` に分離された（例: prompt 7298 / cached 7136 / 差分prefill 162）。
  新規prefill分＝prompt_tokens − cached_tokens。v0.9.x向けの余剰式をそのまま使うと**ヒット時も毎ターン「ミス」と誤判定**する。
  完全なミス（全量prefill）は `cached_tokens == 0` で直接判定できる。LocalLLMChatは「cached_tokens < 前ターンKV×0.5 ならミス」
  （ラウンド単位の部分ヒットで半分以上を捨てた場合もミス扱いに含める。DB v12で `cachedTokens` 保存）
- `stream:false` でもキャッシュ有効（旧「stream:true必須」は撤廃。2026-06-11実証）
- キャッシュヒット判定はログではなく実測値で。**前ターンの `active_kv_tokens` との差分**で判定する:
  `余剰 = prompt_tokens + completion_tokens + 前ターンactive_kv_tokens − 今回active_kv_tokens`
  **余剰 ≈ 0 ならヒット、余剰 ≈ 前ターンactive_kv_tokens ならミス（全量prefill）**
  （ヒット時の `usage.prompt_tokens` は「新規prefill分のみ」になる点に注意）
  - 旧式「`active_kv ≫ prompt+completion` ならヒット」は履歴が小さいと誤判定する
    （履歴24tokの2ターン目で長い応答が出ると、ヒットでも prompt+completion ≈ active_kv になる。2026-07-06実機実証）
  - LocalLLMChat の実装値: 余剰 ≥ 前ターンKV×0.5 かつ 前ターンKV ≥ 100 でミス判定（ヒット・ミス両方向を実機検証済み）。
    2026-10-04以降は `cachedTokens` があればそちらを優先（上記v1.0.x補記）

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
  ※ v0.9.45ではラウンド単位部分ヒットの例外あり（ツール生形式で実測。上記「v0.9.45補記」参照）
- モデル切替・Serve再起動・別セッション切替でキャッシュ破壊。**運用中のmodel名変更は厳禁**
  （キャッシュ全滅＋約46秒のモデルロード）

### 容量超過の危険（実証済み 2026-07-05）

- **容量超過のprefill強行はcheckpoint全滅＝セッション構造的死亡**
  （prefill途中停止→checkpoint 0リセット、復旧手段なし）
- SMには送信前ゲートがあるが、FLM直結クライアントはFLM側に保護がない（クライアント側で防ぐ必要がある）
- **LocalLLMChatは送信前ガード実装済み**（2026-10-06）: 履歴KV実測＋入力見積り（TokenEstimator）で
  max_tokens を残り容量へ自動切り詰め、最低応答枠256すら取れない時のみブロック。新規会話の1通目は
  全会話で最後に受け取った `max_kv_token_capacity` で判定（ctx-lenを上げた直後は古い値で誤ブロック
  しうるため警告に「それでも送る」）。再生成・編集経路はガード対象外

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
- 12b（2026-10〜）は `start_flm.ps1` 経由が標準（Windows AI・空きRAMチェック後に
  `flm serve gemma4-it:12b --ctx-len 16384 --pmode turbo --port 52625 --host 0.0.0.0 --prefill-chunk-len 2048`）
- `/v1/models`はカタログ全体を返す（ロード中モデルの検出には使えない）
