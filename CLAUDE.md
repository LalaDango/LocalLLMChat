# LocalLLMChat

## プロジェクト概要
Kotlin / Jetpack Compose の Android チャットアプリ。端末の NPU 上で動作する FastFlowLM（OpenAI 互換 API）と SSE ストリーミング通信し、ローカル LLM とリアルタイムに会話できる。

- Markwon による Markdown レンダリング（native TextView + AndroidView）
- Room DB でメッセージ・会話を永続化、DataStore で設定保存
- マルチモーダル対応（画像添付 → Base64 → ImageUrlPart）
- メッセージ単位の要約機能（コンテキストウィンドウ節約）

## テクノロジースタック

| カテゴリ | ライブラリ | バージョン |
|---------|-----------|-----------|
| Core | androidx.core:core-ktx | 1.13.1 |
| Lifecycle | androidx.lifecycle:lifecycle-runtime-ktx | 2.8.6 |
| Lifecycle | androidx.lifecycle:lifecycle-viewmodel-compose | 2.8.6 |
| Activity | androidx.activity:activity-compose | 1.9.2 |
| Compose | compose-bom | 2024.09.02 |
| Compose | material3, material-icons-extended | BOM管理 |
| Navigation | androidx.navigation:navigation-compose | 2.8.1 |
| DB | androidx.room:room-runtime / room-ktx | 2.6.1 |
| DB (KSP) | androidx.room:room-compiler | 2.6.1 |
| Network | com.squareup.retrofit2:retrofit | 2.11.0 |
| Network | com.squareup.retrofit2:converter-gson | 2.11.0 |
| Network | com.squareup.okhttp3:okhttp | 4.12.0 |
| Network | com.squareup.okhttp3:logging-interceptor | 4.12.0 |
| Preferences | androidx.datastore:datastore-preferences | 1.1.1 |
| Async | org.jetbrains.kotlinx:kotlinx-coroutines-android | 1.8.1 |
| Markdown | io.noties.markwon:core | 4.6.2 |
| Markdown | io.noties.markwon:ext-tables | 4.6.2 |
| Markdown | io.noties.markwon:html | 4.6.2 |
| Markdown | io.noties.markwon:ext-strikethrough | 4.6.2 |

**SDK**: compileSdk 34 / minSdk 26 / targetSdk 34
**Kotlin**: 2.0.20 / **KSP**: 2.0.20-1.0.25 / **AGP**: 8.5.2
**Java**: 11（source + target）

## プロジェクト構造

```
app/src/main/java/com/example/localllmchat/
├── data/
│   ├── local/           # Room DB
│   │   ├── AppDatabase.kt
│   │   ├── ConversationDao.kt
│   │   ├── ConversationEntity.kt
│   │   ├── MessageDao.kt
│   │   └── MessageEntity.kt
│   ├── remote/          # Retrofit API
│   │   ├── ApiChatMessage.kt
│   │   ├── ApiClient.kt
│   │   ├── ChatApi.kt
│   │   ├── ChatRequest.kt
│   │   ├── ChatResponse.kt
│   │   └── UsageResponse.kt
│   └── repository/
│       ├── ChatRepository.kt
│       └── SettingsRepository.kt
├── ui/
│   ├── screen/
│   │   ├── chat/
│   │   │   ├── AttachmentPreview.kt
│   │   │   ├── ChatScreen.kt
│   │   │   ├── ChatViewModel.kt
│   │   │   ├── SessionTokenCounter.kt
│   │   │   └── TokenInfoDialog.kt
│   │   ├── conversationlist/
│   │   │   ├── ConversationListScreen.kt
│   │   │   └── ConversationListViewModel.kt
│   │   └── settings/
│   │       ├── SettingsScreen.kt
│   │       └── SettingsViewModel.kt
│   ├── navigation/
│   │   └── NavGraph.kt
│   └── theme/
│       ├── Color.kt
│       ├── Theme.kt
│       └── Type.kt
├── util/
│   └── FileProcessor.kt
├── LocalLLMChatApp.kt      # Application（手動DI）
└── MainActivity.kt          # エントリーポイント
```

## 主要ファイルマップ

### エントリーポイント・DI

| ファイル | 役割 | 依存先 |
|---------|------|--------|
| `LocalLLMChatApp.kt` | Application。DB・Repository のシングルトン生成 | AppDatabase, ChatRepository, SettingsRepository |
| `MainActivity.kt` | Activity。edge-to-edge 有効化、NavGraph セットアップ | LocalLLMChatApp, NavGraph |

### データ層 — ローカル DB (Room)

| ファイル | 役割 | 備考 |
|---------|------|------|
| `AppDatabase.kt` | Room DB 定義 (version 3)。Migration 1→2（トークン追跡）、2→3（要約機能） | シングルトン |
| `ConversationEntity.kt` | 会話テーブル: id, title, createdAt, updatedAt | |
| `ConversationDao.kt` | 会話 CRUD。`getAllConversations()` は Flow（updatedAt DESC） | |
| `MessageEntity.kt` | メッセージテーブル: content, role, tokens, speeds, summaryText, isSummarized | conversationId で索引 |
| `MessageDao.kt` | メッセージ CRUD + `updateSummary()` | |

### データ層 — リモート API (Retrofit)

| ファイル | 役割 | 備考 |
|---------|------|------|
| `ApiClient.kt` | Retrofit シングルトン構築。OkHttp (logging=HEADERS, read=300s) | baseURL キャッシュで再利用 |
| `ChatApi.kt` | `POST /v1/chat/completions`。`chat()` / `chatStream()` / `chatStreamMultimodal()` | Bearer token 認証 |
| `ChatRequest.kt` | リクエストボディ: model, messages, stream, temperature=0.45, maxTokens=8192 | |
| `ChatResponse.kt` | レスポンス: choices (message/delta), usage | |
| `ApiChatMessage.kt` | マルチモーダル対応メッセージ。sealed `MessageContent` (Text/Parts) + カスタム Gson Serializer | |
| `UsageResponse.kt` | トークン使用量 + 速度 (decodingSpeedTps, prefillSpeedTps) | |

### データ層 — Repository

| ファイル | 役割 | 依存先 |
|---------|------|--------|
| `ChatRepository.kt` | メッセージ送信（SSE ストリーミング）、要約、履歴構築、自動タイトル生成 | MessageDao, ConversationDao, ApiClient, SettingsRepository |
| `SettingsRepository.kt` | DataStore Preferences で設定管理 (baseUrl, modelName, contextWindowSize, systemPrompt) | DataStore |

### UI 層 — チャット画面

| ファイル | 役割 | 依存先 |
|---------|------|--------|
| `ChatScreen.kt` | LazyColumn + MessageBubble + StreamingMessageBubble。スクロール制御、ファイル添付、要約UI | ChatViewModel |
| `ChatViewModel.kt` | ChatUiState 管理。sendMessage / summarizeMessage / トークン集計 | ChatRepository |
| `SessionTokenCounter.kt` | "Context: X / Y tokens" 表示。色分け (緑→黄→赤) | |
| `TokenInfoDialog.kt` | トークン詳細ダイアログ | |
| `AttachmentPreview.kt` | 添付ファイルプレビュー（テキスト/画像） | |

### UI 層 — 会話一覧・設定

| ファイル | 役割 | 依存先 |
|---------|------|--------|
| `ConversationListScreen.kt` | 会話リスト表示、新規作成FAB、削除、設定遷移 | ConversationListViewModel |
| `ConversationListViewModel.kt` | 会話一覧の読み込み・作成・削除 | ChatRepository |
| `SettingsScreen.kt` | 設定フォーム。モデル名はドロップダウン (`PRESET_MODELS`) | SettingsViewModel |
| `SettingsViewModel.kt` | 設定の読み書き | SettingsRepository |

### UI 層 — ナビゲーション・テーマ

| ファイル | 役割 |
|---------|------|
| `NavGraph.kt` | 3ルート: `conversation_list` (start) → `chat/{conversationId}` → `settings` |
| `Color.kt` | メッセージバブル色、要約色、Think ブロック色、トークン警告色 |
| `Theme.kt` | Dynamic Colors (Android 12+) + フォールバック |
| `Type.kt` | Material3 タイポグラフィ |

### ユーティリティ

| ファイル | 役割 |
|---------|------|
| `FileProcessor.kt` | テキスト添付 (24KB上限) / 画像添付 (1024px リサイズ, Base64) の処理 |

## データフロー

```
User Input (ChatScreen)
    ↓
ChatViewModel.sendMessage()
    ↓
ChatRepository.sendMessage()
    ├→ MessageDao.insert()          ... ユーザーメッセージ保存
    ├→ MessageDao.getMessages...()  ... 履歴取得（要約済みなら summaryText を使用）
    ├→ SettingsRepository           ... baseUrl, modelName, systemPrompt 取得
    ├→ ApiClient → ChatApi          ... SSE ストリーミングリクエスト
    │     ↓ (BufferedReader, 32ms throttle)
    │   onStreamUpdate callback → ChatViewModel → streamingContent/Reasoning
    │     ↓
    │   ChatScreen 再描画 (StreamingMessageBubble)
    └→ MessageDao.insert()          ... アシスタントメッセージ保存（トークン情報付き）
         ↓
    Flow<List<MessageEntity>> → ChatViewModel → ChatUiState.messages
         ↓
    ChatScreen 再描画 (MessageBubble + Markwon)
```

### 要約フロー
```
ChatViewModel.summarizeMessage(messageId, content)
    ↓
ChatRepository.summarizeMessage()
    ├→ ChatApi.chat() (非ストリーミング, timeout=180s)
    ├→ <think>タグ除去
    ├→ トークン計算: original = promptTokens - 50, summary = completionTokens
    └→ MessageDao.updateSummary(messageId, summaryText)
```

## 設定値デフォルト (SettingsRepository)

| キー | デフォルト値 |
|-----|------------|
| baseUrl | `http://localhost:8080` |
| modelName | `lfm2.5-tk:1.2b` |
| contextWindowSize | `32768` |
| systemPrompt | `""` (空) |

## プリセットモデル (SettingsScreen)

`gemma3:4b`, `lfm2.5-it:1.2b`, `lfm2.5-tk:1.2b`, `qwen2.5vl-it:3b`, `qwen3-tk:4b`, `qwen3vl-it:4b`, `qwen3:8b`

## ビルド・実行手順

1. Android Studio で `c:\dev\LocalLLMChat` を開く
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

### 要約機能
- タイムアウト 180 秒（小型モデルの Prefill で 10 秒以上かかる場合がある）
- トークン計算: `originalTokens = promptTokens - 50`（system prompt 固定オフセット）
- 履歴構築時: `isSummarized == true` なら `summaryText` を API に送信

### DB マイグレーション
- 現在 version 3。新しいカラム追加時は `AppDatabase.kt` に Migration を追加すること
- Migration 1→2: トークン追跡カラム (promptTokens, completionTokens, totalTokens, speeds)
- Migration 2→3: 要約カラム (summaryText, isSummarized)

### DI
- Hilt/Dagger 不使用。`LocalLLMChatApp` で手動シングルトン生成
- 新しい Repository/依存追加時は `LocalLLMChatApp.kt` を編集

### ネットワーク
- `network_security_config.xml` で cleartext traffic を許可（ローカル HTTP 接続用）
- OkHttp タイムアウト: connect=60s, read=300s, write=60s
