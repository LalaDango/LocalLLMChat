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
- ツールを使った会話も毎ターン全量 prefill が宿命（checkpoint には生成実物の `<|tool_call>` トークン列が刻まれるが、再構築履歴の assistant には本文しか無い構造的不一致。`<think>` 除去と同構図。数百トークン規模では実害なし、2026-07-07 実測）

### Tool Calling
- ツール実行は最大 `MAX_TOOL_ROUNDS`(=3) ラウンドまでループ（`ChatRepository.generateResponse()`）。カウントは全ツール共通のラウンド数（1応答に複数 tool_calls でも1ラウンド）。上限到達後の tool_calls は実行されずテキスト扱いで打ち切り（暴走防止）。本文が空なら打ち切り文言を保存（空バブル防止）
- **gemma4系の role 変換**: gemma4 は FLM 経由だと role:"tool" がモデルに届かない（テンプレート実装が落とす）ため、`buildApiMessages()` が送信時のみ変換する（DB・UI は無変更）。判定は `ToolRegistry.requiresToolRoleConversion(modelName)`（modelName に "gemma4" を含む）。内容: ① role:"tool" → role:"user"（各行 `[ツール name(args) の実行結果] result` + 末尾に「この結果を踏まえて応答してください」1回。連続 tool は1つの user にマージ＝交互制約対策）② assistant の tool_calls は送らず、畳み込みテキストも置かない（本文があれば本文のみ、空ならメッセージごとスキップ）。変換は DB 行 + modelName の純関数（決定的、checkpoint 照合を崩さない）。他モデルは従来どおり role:"tool" のまま
- **assistant 側に呼び出し書式を置くと模倣される**: v1 で assistant content に `[ツール呼び出し: name(args)]` を畳んだところ、モデルが「自分の発言フォーマット」と学習し2問目で平文模倣（本物の tool_call を出さない）が発生 → 呼び出し情報（name+args）は user 側の結果行に統合した。args は必須（e4b は本文なし tool_call のみを返すことがあり、クイズ質問文等が args にしか無い）
- **e4b のツール発火は「ムラ」でなく「閾値」**: プロンプトにツール名が明示されないと発火しない（「ツールで」だけ→0/5）。ツール名を書けば言語不問で100%（JA/EN 10/10、get_datetime 5/5。2026-07-07 ハーネス実測）。アプリ側で直すものはなく、プロンプト/プリセットにツール名を書く運用で制御する

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
