# LocalLLMChat

## プロジェクト概要
Kotlin / Jetpack Compose の Android チャットアプリ。端末の NPU 上で動作する FastFlowLM（OpenAI 互換 API）と SSE ストリーミング通信し、ローカル LLM とリアルタイムに会話できる。

- Markwon による Markdown レンダリング（native TextView + AndroidView）
- Room DB でメッセージ・会話を永続化、DataStore で設定保存
- マルチモーダル対応（画像添付 → Base64 → ImageUrlPart）
- メッセージ単位の要約機能（コンテキストウィンドウ節約）

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

### メッセージ除外機能
- 個別メッセージをAPI送信履歴から除外するトグル機能（コンテキストウィンドウ節約）
- 除外されたメッセージは `ChatRepository.sendMessage()` の履歴構築時にフィルタ（要約チェックの前段階）
- 除外中のバブルは `alpha(0.45f)` で半透明表示、`VisibilityOff` アイコンが `error` 色に変化
- `SessionTokenCounter` は除外メッセージのトークンを集計から除外
- 要約機能と独立（要約済みメッセージもさらに除外可能）

### 要約機能
- タイムアウト 180 秒（小型モデルの Prefill で 10 秒以上かかる場合がある）
- トークン計算: `originalTokens = promptTokens - 50`（system prompt 固定オフセット）
- 履歴構築時: `isSummarized == true` なら `summaryText` を API に送信

### DB マイグレーション
- 現在 version 4。新しいカラム追加時は `AppDatabase.kt` に Migration を追加すること
- Migration 1→2: トークン追跡カラム (promptTokens, completionTokens, totalTokens, speeds)
- Migration 2→3: 要約カラム (summaryText, isSummarized)
- Migration 3→4: メッセージ除外カラム (isExcluded)

### DI
- Hilt/Dagger 不使用。`LocalLLMChatApp` で手動シングルトン生成
- 新しい Repository/依存追加時は `LocalLLMChatApp.kt` を編集

### ネットワーク
- `network_security_config.xml` で cleartext traffic を許可（ローカル HTTP 接続用）
- OkHttp タイムアウト: connect=60s, read=300s, write=60s
