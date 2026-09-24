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
./gradlew connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
                                   # 画面操作のテスト（起動中のエミュレータ・実機で動く）
./gradlew assembleDebugAndroidTest # 画面操作のテストのコンパイルだけ（端末なしで通せる）
./gradlew lintDebug                # lint
./gradlew assembleRelease          # リリースAPK（R8 + 署名）
./gradlew bundleRelease            # Play アップロード用 AAB
```

リリース署名には `keystore.properties`（リポジトリ外）が必要。無くてもビルドは成功するが未署名になる。

## プライバシー

動画の実体・撮影内容・個人情報を外部サーバーへ送信することはない。処理はすべて端末内で完結する（広告・アナリティクスSDKなし）。

## ライセンス

- **このリポジトリのソースコード**：MIT ライセンス（[LICENSE](./LICENSE)）。
- **配布している APK**：GNU General Public License バージョン3（またはそれ以降の版）。
  書き出しに使っている FFmpeg（[ffmpeg-kit-android-16KB](https://github.com/moizhassankh/ffmpeg-kit-android-16KB) 6.1.1、
  `com.moizhassan.ffmpeg:ffmpeg-kit-16kb`）が `--enable-gpl --enable-version3` でビルドされており、
  それを含む APK 全体が GPLv3 の条件になるため。MIT は GPLv3 と両立する。
  - GPLv3 の全文は APK に同梱している（`app/src/main/assets/licenses/GPL-3.0.txt`）。
    アプリ内では「編集内容の保存」ダイアログの「ライセンス」から、著作権表示・無保証の旨・ソースの場所・全文を見られる。
  - 対応するソースコード：アプリはこのリポジトリ、FFmpeg はビルド用スクリプトを含めて上記フォークのタグ `6.1.1`、
    FFmpeg 本体は [arthenica/FFmpeg](https://github.com/arthenica/FFmpeg) のタグ `n6.0`
    （一緒にビルドされるライブラリの版と入手先は、フォークの `scripts/source.sh` に固定されている）。
    フォークと FFmpeg 本体のソースの控えは、[Releases](https://github.com/msmn1128/MyVlogApp/releases) の各リリースに添付してある。
- 同梱の素材（`app/src/main/assets/fonts/`・`sfx/`）はそれぞれの配布元のライセンスに従う。
  アプリ内の「ライセンス」にも、フォントの著作権表示を載せている。
  - M PLUS U（`MPLUSU-Regular.ttf`）：Copyright 2025 The M+ FONTS Project Authors。[SIL Open Font License 1.1](https://openfontlicense.org)
  - 07ロゴたいぷゴシック7（`LogoTypeGothic.otf`）：Copyright (c) 2013 M+ FONTS PROJECT／[フォントな](http://www.fontna.com)
  - タイトルの効果音（`sfx/title.mp3`）：表記の要らない素材

