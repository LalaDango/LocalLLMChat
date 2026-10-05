# LocalLLMChat

## プロジェクト概要
Kotlin / Jetpack Compose の Android チャットアプリ。PC の NPU 上で動作する FastFlowLM（OpenAI 互換 API）と Tailscale 経由で SSE ストリーミング通信し、ローカル LLM とリアルタイムに会話できる。

サーバー側環境（FastFlowLM v0.9.45 / 主力モデル gemma4-it:e4b / NPU 7.6GB 制約 / KV キャッシュ挙動）の詳細は `.claude/skills/local-ai-env-ref/` を参照。環境判定はメモリの旧記述よりこのスキルを優先すること。

このスキルは **LocalLLMChat 特化のスリム版**で、Claude.ai 側のフル版 .skill（SM 含む環境全体の正本）とは**別ディストリビューション**。ファイル一致は目指さない。新知見が出たら双方向に「内容」を選別して還流する（ファイル丸ごとコピーで上書きしない。フル版由来の SM/Vane/モバイル文脈は取り込まない）。

## ビルド・実行手順

1. Android Studio で開く
2. Gradle sync を実行
3. `./gradlew assembleDebug` または Android Studio の Run でビルド
4. 実機/エミュレータにインストール
5. **前提**: FastFlowLM サーバーが `baseUrl` で起動していること（設定画面で変更可能）

## 開発時の注意点

### SSE ストリーミング
- `HttpLoggingInterceptor.Level.BODY` は**使用禁止**（レスポンス全体をバッファリングし SSE が壊れる）
- `Level.HEADERS` を使うこと
- UI 更新は 32ms スロットルで制御（過剰な recomposition 防止）

### LazyColumn + AndroidView (Markwon)
- AndroidView (TextView) はビューポート進入時に高さ再計測 → スクロールジャンプの原因
- `messageHeightCache: mutableStateMapOf<Long, Int>` で高さキャッシュし `defaultMinSize` で適用
- `Card` / `Surface` は内部で `clip(shape)` → タッチ hit-test 遮断の問題あり → `Box` + `background(color, shape)` を推奨

### スクロール制御
- `isScrollInProgress` はプログラムスクロールにも反応 → `collectIsDraggedAsState()` で物理ドラッグのみ検出
- `scrollToItem(index)` はアイテム TOP → BOTTOM は `scrollToItem(index, Int.MAX_VALUE)`
- `snapshotFlow` は暗黙の `distinctUntilChanged` → 同値連続は drop される

### `<think>` タグ処理
- ストリーミング中の不完全タグは `cleanupIncompleteThinkTags()` で修正
- 要約レスポンスからも `<think>` タグを除去（モデル非依存）

### キャッシュ親和性（FLM checkpoint 照合）
- `buildApiMessages()` は送信時のみ U+3000 → 半角スペース正規化（`normalizeForApi()`。DB・表示は変更しない）
- 履歴を書き換える操作（除外トグル・要約適用・ブランチ切替・編集/再生成・ツールON/OFF）の次ターンは全量 prefill → Snackbar でヒント表示
- assistant 履歴の `<think>` 除去 + trim は生成実物とのズレ → thinking を出すモデル（qwen系）では毎ターンキャッシュミス1回分の宿命。qwen 系で「毎ターン遅い」と感じたらこれが原因（e4b では実害なし、対応不要）
- ツール会話のキャッシュ税（v3 現行・2026-07-13 実機確認）: 純 tool_call ターンは部分ヒット成立（差分 prefill・税なし）、**分割 echo ラウンドと strip 発動ラウンドのみ当該ターン全量 1 回**。v2 フォールバックはラウンドごと 1 回。部分ヒット時の `prompt_tokens` は v0.9.x では差分値報告なので絶対値でのミス判定は不可（余剰式を使う）。**FLM v1.0.x は `prompt_tokens` が全量・復元分が `prompt_tokens_details.cached_tokens`** → アプリの full prefill 判定は `cachedTokens` があればそれを優先（2026-10-04）。詳細はスキル known-issues.md「ツール会話のキャッシュ税」

### Tool Calling
- **tool 呼び出し関連の作業ではスリム版 env-ref（`.claude/skills/local-ai-env-ref/references/known-issues.md` のツール連携節）必読**（v0.9.45 の 2 バグ・v3 要件・キャッシュ税・地雷リストが集約されている）
- ツール実行は最大 `MAX_TOOL_ROUNDS`(=3) ラウンドまでループ（`ChatRepository.generateResponse()`）。カウントは全ツール共通のラウンド数（1応答に複数 tool_calls でも1ラウンド）。上限到達後の tool_calls は実行されずテキスト扱いで打ち切り（暴走防止）。本文が空なら打ち切り文言を保存（空バブル防止）
- **gemma4系は v3（生 OpenAI 形式）が現行実装（2026-07-13 ハーネス/実機検証グリーン）**: ゲートは `ToolRegistry.usesV3ToolProfile(modelName)`（"gemma4" contains）。非 gemma4 は挙動不変。内容: ①ツールターンは stream 受信（従来から充足。stream:false は FLM バグA で本文欠落）②混在 assistant（content+tool_calls）は text-only → tool_calls-only の**分割 echo**（バグB 対策。本文なしは tool_calls-only 1件・content:null）③孤児 tool は drop、tool 結果を失った tool_calls は text-only 降格（双方向ガード＋Log.w）④捏造断片 strip（`ToolFragmentSanitizer`・実物シグネチャでユニットテスト固定）＋ツールターン max_tokens `min(設定値, 2048)` キャップ ⑤発火はツール名焼き込み（プロンプト運用）。整形はフィルタ済み DB 行 + modelName の純関数（決定的、checkpoint 照合を崩さない）
- **v2 変換はフォールバック残置**: `buildApiMessages()` の変換コード（tool→user マージ・tool_calls 抑止）は無傷。`ToolRegistry` の v2 モデルリスト（現在空）に部分文字列を足せば再有効化できる。旧レシピの詳細はスキル known-issues.md
- **捏造 tool_call は FLM が本物としてパースし実行系に流れる**（strip が消せるのは本文断片のみ）。1ラウンド複数ダイアログの形で顕在化するが max_tokens キャップで有界。既知の改善候補: `ToolRegistry.execute()` は disabledTools を見ないため per-tool OFF 中のツールも名指し発火なら実行される
- **assistant 側に呼び出し書式を置くと模倣される**: v1 で assistant content に `[ツール呼び出し: name(args)]` を畳んだところ、モデルが「自分の発言フォーマット」と学習し2問目で平文模倣（本物の tool_call を出さない）が発生 → 呼び出し情報（name+args）は user 側の結果行に統合した。args は必須（e4b は本文なし tool_call のみを返すことがあり、クイズ質問文等が args にしか無い）
- **e4b のツール発火は「ムラ」でなく「閾値」**: プロンプトにツール名が明示されないと発火しない（「ツールで」だけ→0/5）。ツール名を書けば言語不問で100%（JA/EN 10/10、get_datetime 5/5。2026-07-07 ハーネス実測）。アプリ側で直すものはなく、プロンプト/プリセットにツール名を書く運用で制御する
- **多段クイズの停滞**: 「次の問題です」と宣言して tool_call を出さず止まることがある（上限打ち切りと紛らわしいが Logcat の limit 警告なしで区別）→「続けて。必ず ○○ ツールを呼んで」1発で復帰（userターンでラウンドもリセット）

### メッセージ除外機能
- `ChatRepository.sendMessage()` の履歴構築時に `isExcluded` でフィルタ（要約チェックの前段階）
- `SessionTokenCounter` は除外メッセージのトークンを集計から除外
- 要約機能と独立（要約済みメッセージもさらに除外可能）

### 要約機能
- タイムアウト 180 秒（小型モデルの Prefill で 10 秒以上かかる場合がある）
- トークン計算: `originalTokens = promptTokens - 50`（system prompt 固定オフセット）
- 履歴構築時: `isSummarized == true` なら `summaryText` を API に送信

### DB マイグレーション
- 現在 version 12（4→5 翻訳、5→6 tool calling、6→7 ブランチ、7→8 要約設定、8→9 プリセット、9→10 KV実測値、10→11 画像永続化 message_images、11→12 cachedTokens）
- 新しいカラム追加時は `AppDatabase.kt` に Migration を追加すること

### DI
- Hilt/Dagger 不使用。`LocalLLMChatApp` で手動シングルトン生成
- 新しい Repository/依存追加時は `LocalLLMChatApp.kt` を編集

### ネットワーク
- `network_security_config.xml` で cleartext traffic を許可（ローカル HTTP 接続用）
- OkHttp タイムアウト: connect=60s, read=300s, write=60s
