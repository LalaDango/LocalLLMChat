# LocalLLMChat

## プロジェクト概要
Kotlin / Jetpack Compose の Android チャットアプリ。PC の NPU 上で動作する FastFlowLM（OpenAI 互換 API）と Tailscale 経由で SSE ストリーミング通信し、ローカル LLM とリアルタイムに会話できる。

サーバー側環境（FastFlowLM v0.9.43 / 主力モデル gemma4-it:e4b / NPU 7.6GB 制約 / KV キャッシュ挙動）の詳細は `.claude/skills/local-ai-env-ref/` を参照。環境判定はメモリの旧記述よりこのスキルを優先すること。

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

### Tool Calling
- ツール実行は最大 `MAX_TOOL_ROUNDS`(=3) ラウンドまでループ（`ChatRepository.generateResponse()`）。カウントは全ツール共通のラウンド数（1応答に複数 tool_calls でも1ラウンド）。上限到達後の tool_calls は実行されずテキスト扱いで打ち切り（暴走防止）。本文が空なら打ち切り文言を保存（空バブル防止）

### メッセージ除外機能
- `ChatRepository.sendMessage()` の履歴構築時に `isExcluded` でフィルタ（要約チェックの前段階）
- `SessionTokenCounter` は除外メッセージのトークンを集計から除外
- 要約機能と独立（要約済みメッセージもさらに除外可能）

### 要約機能
- タイムアウト 180 秒（小型モデルの Prefill で 10 秒以上かかる場合がある）
- トークン計算: `originalTokens = promptTokens - 50`（system prompt 固定オフセット）
- 履歴構築時: `isSummarized == true` なら `summaryText` を API に送信

### DB マイグレーション
- 現在 version 11（4→5 翻訳、5→6 tool calling、6→7 ブランチ、7→8 要約設定、8→9 プリセット、9→10 KV実測値、10→11 画像永続化 message_images）
- 新しいカラム追加時は `AppDatabase.kt` に Migration を追加すること

### DI
- Hilt/Dagger 不使用。`LocalLLMChatApp` で手動シングルトン生成
- 新しい Repository/依存追加時は `LocalLLMChatApp.kt` を編集

### ネットワーク
- `network_security_config.xml` で cleartext traffic を許可（ローカル HTTP 接続用）
- OkHttp タイムアウト: connect=60s, read=300s, write=60s
