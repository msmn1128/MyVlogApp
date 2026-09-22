# MyVlog.

撮った動画を並べて、ひとことと撮影時刻を焼き込み、1本のVLOGとして書き出すAndroidアプリ。

- パッケージ（Kotlin）: `com.example.myvlogapp`
- applicationId（配布用）: `com.masamune.myvlogapp`
- minSdk 29 / targetSdk 37 / compileSdk 37、Java 17
- Compose（Material 3）、ExoPlayer（プレビュー）、FFmpegKit（書き出し）

設計・実装の詳細は [CLAUDE.md](./CLAUDE.md) を参照。

## ビルド

`./gradlew` を素で叩くと Java が見つからず失敗する。Android Studio 同梱のJBRを指定すること。

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

./gradlew assembleDebug            # デバッグAPK
./gradlew testDebugUnitTest        # JVM単体テスト
./gradlew lintDebug                # lint
./gradlew assembleRelease          # リリースAPK（R8 + 署名）
./gradlew bundleRelease            # Play アップロード用 AAB
```

リリース署名には `keystore.properties`（リポジトリ外）が必要。無くてもビルドは成功するが未署名になる。

## プライバシー

動画の実体・撮影内容・個人情報を外部サーバーへ送信することはない。処理はすべて端末内で完結する（広告・アナリティクスSDKなし）。
