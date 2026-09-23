# MyVlogApp

撮った動画を並べて、ひとことと撮影時刻を焼き込み、1本のVLOGとして書き出すAndroidアプリ。
同じ仕様のiOS版が別リポジトリ（`~/XcodeProjects/MyVlogApp`）にある。

- パッケージ（Kotlin）: `com.example.myvlogapp`
- applicationId（配布用）: `com.masamune.myvlogapp` ← **公開後は変更不可。namespaceとは別物**
- minSdk 29 / targetSdk 37 / compileSdk 37、Java 17
- Compose（Material 3）、ExoPlayer（プレビュー）、FFmpegKit（書き出し）

---

## ビルド・テスト

**`./gradlew` を素で叩くと Java が見つからず失敗する。** Android Studio 同梱のJBRを指定すること。

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

./gradlew assembleDebug            # デバッグAPK
./gradlew testDebugUnitTest        # JVM単体テスト（145件）
./gradlew lintDebug                # lint（現状 0 issues を維持している）
./gradlew assembleRelease          # リリースAPK（R8 + 署名）
./gradlew bundleRelease            # Play アップロード用 AAB
```

成果物と署名の確認:

```bash
ls app/build/outputs/apk/release/app-release.apk      # 約 105MB
ls app/build/outputs/bundle/release/app-release.aab   # 約 53MB
"$HOME/Library/Android/sdk/build-tools/<ver>/apksigner" verify --print-certs -v \
  app/build/outputs/apk/release/app-release.apk
```

### 署名

`keystore.properties`（リポジトリ外、各自のマシンにのみ置く。`.gitignore` 済み）から読む。
**ファイルが無くてもリリースビルドは成功し、無署名のAPKができる。** 配布前は必ず
`apksigner verify` で署名を確認すること。

### リリース前に必ず

`app/build.gradle.kts` の `versionCode` を上げる。Play は同じ versionCode を受け付けない。

---

## 全体像

```
MainActivity            画面構成（縦1カラム / 横2ペイン）、権限、ダイアログ、ポーリング
  └ VlogViewModel       配線と窓口。画面はここだけを見る
      ├ TimelineStore       クリップ一覧・履歴・プレイリスト同期・編集操作の本体
      │    └ EditHistory       もとに戻す / やり直す
      ├ PlaybackController  ExoPlayer・選択位置・再生位置・自動遷移・トリム終端の停止
      ├ ProjectsController  一時保存の保存・上書き・読み出し・削除
      │    └ ClipStore         自動保存・一時保存（SharedPreferences、動画はコピーしない）
      ├ Waveform            音声をMediaCodecでデコードして波形にする
      └ VlogExportService   書き出し（フォアグラウンドサービス）
            └ VlogExporter  書き出しの手順（中身は export/ の各ファイル）
```

画面（`ui/screens/`）は `VlogViewModel` を受け取らない。MainActivity が
`TimelineState`（状態を **StateFlow のまま**）と `TimelineActions`（操作）に組み立てて渡す。
値まで上げないのは再コンポーズ範囲を絞るためで、理由は `ui/screens/TimelineActions.kt` にある。

| パッケージ | 役割 |
|---|---|
| ルート | `VlogModels`(純粋データ) / `VlogConstants`(定数) / `Formatters`(表示整形) / `Parallel`(同時実行数を絞る並列処理) / `VlogViewModel` / `MainActivity` |
| `data/` | `ClipStore`(永続化) `ProjectsController`(一時保存の窓口) `VideoMetadataReader`(撮影日時・尺) `GalleryRepository`(MediaStore) `MediaAccess`(権限) |
| `playback/` | `PlaybackController` とその純粋関数 `playFromWhere` |
| `edit/` | `TimelineStore`(クリップ一覧の持ち主) `EditHistory`（スナップショット型を問わない汎用の履歴） |
| `export/` | 下記「書き出しパイプライン」参照 |
| `waveform/` | 波形の抽出・描画・ジェスチャー・トリマーUI |
| `ui/` | `screens/`（画面の塊） `components/`（共通部品） `theme/` |

---

## 守るべき不変条件

壊すと気付きにくいバグになるもの。変更するときは必ず確認すること。

### ひとこと（TextSegment）

- `texts` は **必ず1件以上**あり、**先頭の `startMs` は必ず 0**、**昇順**。
  この3つが崩れると `textIndexAt` / `visibleTextSpans` が拾えない区間を作り、書き出しから文字が消える。
  復元時に `VlogModels.readTextSegments()` が並べ替えと先頭0への補正を行っている。
- 動画は分割しない。文字だけを `drawtext` の `enable` で時間によって出し分ける。
  だからクリップは何区間に分けても1本のままで、つなぎ目が生まれない。

### クリップ

- `id` は `nextClipId()`（`AtomicLong` の通し番号）でのみ発行する。LazyRowのkeyと、
  撮影時刻の取り直し時の突き合わせに使う。時刻ベースにしないこと。
- 解像度は持たない。書き出しは `scale`+`pad` で1920x1080に入れるだけで入力サイズが要らず、
  プレビューもPlayerViewが動画から直接読む。
- タイムライン上限は `MAX_CLIPS = 100`。全クリップを1回のFFmpeg呼び出しに同時入力するため、
  メモリが本数に比例する（実測: 4GBエミュレータ・1080p、100本で約2.3GB、デコード既定スレッドだと約5.1GBで強制終了）。

### 永続化

- **動画の実体はコピーしない。** URIと編集内容だけを保存する。
- 復元できるのは「いま実際に開けるURI」だけ。`ClipStore.isReadable()` が1件ずつ開いて確かめる
  （MediaStoreのURIとSAFのURIを同じ判定で扱え、移動・削除も同時に弾けるため）。
- SAFの永続権限には保持数上限（Android 11以降で512）がある。`releaseUnreferencedPermissions()` が
  起動時に、タイムラインにも一時保存にも使われていない権限を解放する。
- 自動保存（`vlog_clips`）と一時保存（`vlog_projects`）は**別ファイル**。同居させると、
  ひとことを1文字打つたびの自動保存が、無関係な一時保存ごと（100本×20件で約1MB）書き直してしまう。

---

## 書き出しパイプライン（`export/`）

タイトルカードと全クリップを**仮想タイムライン上に並べ、1回のFFmpeg呼び出しで結合・エンコード**する。
クリップごとに個別エンコードして結合し直すと同じ映像を2回圧縮することになるため。

`VlogExporter.export()` が持つのは手順だけで、各工程は同じパッケージの別ファイルにある。

| ファイル | 役割 |
|---|---|
| `VlogExporter.kt` | `export()` の手順、`AudioPlan`、強制終了時の後始末2種 |
| `FilterGraph.kt` | `filter_complex` の組み立て。**下の地雷はほぼすべてここ** |
| `ExportTextFiles.kt` | drawtextへ渡す行ごとのテキストファイル |
| `ExportAssets.kt` | フォント・効果音のassetsからの展開 |
| `FFmpegCapabilities.kt` | 使えるエンコーダ・フィルタの判定と出力フォーマット |
| `FFmpegRunner.kt` | 実行・進捗・中止（実行中セッションを控えるのはここだけ） |
| `GalleryOutput.kt` | MediaStoreへの保存、ファイル名の連番 |
| `VlogExportService.kt` / `ExportStatus.kt` | フォアグラウンドサービスと、プロセス共有の進行状態 |

```
-i 効果音(タイトルありのときのみ) -i clip1 -i clip2 ... -filter_complex_script graph.txt
  → [vout][aout] → H.264 + AAC → cacheDir → MediaStore(Movies/MyVlogApp)
```

踏んではいけない地雷:

- **`-ss` / `-t` は `-i` の前に置く。** フィルタの `trim` だけでトリミングすると、素材の先頭から
  開始位置まで全部デコードして捨てるため、後ろの方を切り出すほど遅くなる。
- **各入力に `-threads 1`。** デコーダのスレッドごとのフレームバッファが本数ぶん積み上がる。
- **音声は `apad` → `atrim` で映像と同じ尺に強制する。** 素材の音声は映像より数十ms短いことがあり、
  その差がconcatのセグメントごとに積み上がって後半の音がずれる。`apad` は終端を指定しないと
  無限に無音を継ぎ足すので `atrim` と必ず対で使う。
- **全クリップに `setsar=1`。** 非正方画素の素材が混ざるとタイトルカードとSARが食い違い、
  concatが `Input link parameters do not match` で書き出しごと失敗する。
- **`drawtext` には `:expansion=none`。** `%{...}` の展開を切っている。既定のままだと、
  ひとことに `%` が入っているだけで表示が壊れたりパースエラーで失敗したりする。
- **文字は必ず `textfile=` 経由、1行1ファイル。** `text=` に直接埋めると引用符・コロン・
  バックスラッシュの解釈が中身次第になる。また、このFFmpegビルド(6.x)に `text_align` が無いため、
  複数行の中央揃えは「1行につき1つのdrawtext」で実現している。
- **フィルタグラフは `-filter_complex_script` でファイル渡し。** 100本で約70KBになる。
- **秒数は必ず `Locale.US` 固定**（`ffmpegSeconds`）。小数点にカンマを使うロケールで壊れる。

### 中止

`VlogExporter.cancel()` は `runningSessionId` を狙い撃ちする。引数なしの `FFmpegKit.cancel()` は
全セッションを止めるため、機能判定（`-encoders`/`-filters`、初回の書き出しの冒頭で実行し、成功した結果だけを覚える）を
巻き込んで結果を汚す。セッションIDが分かる前に押された場合は `cancelRequested` が拾う。

中止・サービス破棄の経路は3つあり、**すべてが `VlogExporter.cancel()` を呼ぶ必要がある**
（コルーチンを止めるだけではネイティブのエンコードが走り続ける）:
`onStartCommand(ACTION_CANCEL)` / `onTimeout` / `onDestroy`。

### 強制終了されたときの後始末

書き出し中にプロセスごと回収されると、2箇所にゴミが残る。どちらも `VlogViewModel` の
init から、**書き出しが走っていないときだけ**掃除する。

- MediaStore に `IS_PENDING` のままの項目 → `cleanupOrphanedPendingFiles()`
- cacheDir に結合途中の動画（数GBになりうる） → `cleanupOrphanedWorkFiles()`

---

## 再生（`PlaybackController`）

- クリップ一覧は持たず、`clips: () -> List<VlogClip>` で毎回最新を覗く。
  選択位置(`selectedIndex`)と再生位置(`playbackPositionMs`)だけがこのクラスの持ち物。
- **トリム終端の監視は `PLAYBACK_POLL_INTERVAL_MS`(80ms) のポーリング**で行う。
  MainActivity が `repeatOnLifecycle(STARTED)` と `collectLatest(isPlaying)` の二重ゲートで回す
  （前面かつ再生中のときだけ）。一時停止中の位置あわせはポーリングではなく
  `seekWithoutPause` / `seekAndPause` が直接行っている。
- 終端検知は**2経路ある**。ポーリングの `enforceTrimBounds` と、`STATE_ENDED` のリスナー。
  トリム終端が動画の実際の末尾と一致していると、ポーリングが気付く前にExoPlayerが
  ENDEDへ進んで `isPlaying` が false になり、ポーリングが素通りするため。
- ドラッグ中は `isInteractiveSeeking` でポーリングの上書きを止める。`seekTo()` は非同期で、
  直後の `currentPosition` が古い値を返すことがあり、シークのピンが跳ねて見える。

---

## 撮影時刻の決め方（`VideoMetadataReader`）

1. 埋め込みの `creation_time`（ただしエポック以前＝MP4起点の1904年は「無い」扱い）
2. MediaStoreの `DATE_TAKEN`
3. ファイル名の日時（`PXL_20260901_101500` など）
4. ファイルの更新日時
5. 端末への追加日時 ← ここだけ `reliable = false`
6. どれも取れなければ追加時刻（`reliable = false`）

- **`MediaMetadataRetriever` はプロセス全体で直列化している**（`RETRIEVER_LOCK`）。
  同時に使うと**別の動画の撮影日時が返ることがある**（尺や幅は正しいまま日付だけ入れ替わる。
  実測でAndroid 17エミュレータ、4本同時で1000回に数回）。`getVideoMetadata` の中で守っているので、
  呼び出し側は気にしなくてよい。
- `reliable = false` のクリップは次回起動時に1度だけ取り直し、そこで取れなければ
  `shotAtRefreshed` を立てて二度と試さない（毎起動の直列読み直しを避けるため）。

---

## コードの書き方

- **コメントは「何をしているか」ではなく「なぜそうなっているか」を日本語で書く。**
  特に、素直な実装を採らなかった箇所は理由を必ず残す。このリポジトリのコメントの大半は
  実機で踏んだ不具合の記録になっている。消す前に、その理由がまだ有効か確かめること。
- **コミットメッセージも日本語で、「何が問題だったか」を書く。** 末尾に
  `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`。
- 数値は `VlogConstants.kt` に集約する。UI側とExporter側で同じ値を使う箇所が多く、
  散らすと片方だけ変えて見た目が食い違う。
- 画面に出す文字列はKotlin側にベタ書き。多言語化の予定が無いため。
  `strings.xml` にあるのはアプリ名（`app_name` = "MyVlog."）だけ。
- Composeでは、80msごとに変わる再生位置を**コンポジション中で読まない**。
  `State<Long>` のまま渡し、`derivedStateOf` で「表示する文字列」「区切りの上か」へ派生させてから読む。
  直接読むと画面全体が毎回再コンポーズされる。
- `super.onCleared()` は呼ばない（`@EmptySuper` が付いており、呼ぶと lint が警告する）。

---

## テスト

JVM単体テスト（`src/test`）のみ、145件。対象は純粋関数と、再生側を偽物に差し替えた `TimelineStore`。

| ファイル | 対象 |
|---|---|
| `VlogClipTest` | 尺・区間・分割点の判定、区間ごと移動でずらせる量（`clampTimelineShift`） |
| `VlogClipJsonTest` | JSONの往復、旧保存データとの互換、`texts`の正規化（1件以上・先頭0・昇順） |
| `MergeAndFormatTest` | 撮影日時順の差し込み、連番付け、表示整形 |
| `AddClipsSpecTest` | 追加時のスキップ通知、選択順 |
| `ProjectSpecTest` | 一時保存の読み出し可否、保存領域の移行 |
| `PlaybackSpecTest` | 再生ボタンの頭出し判断（`playFromWhere`） |
| `EditHistoryTest` | 履歴のまとめ判定・上限・undo/redo・積んだ状態の書き換え |
| `TimelineStoreTest` | 区切りの移動範囲、ひとことの書き換え・分割、undo/redo後の音量、撮影時刻の取り直しとundo |
| `FilterGraphTest` | FFmpegフィルタグラフの組み立て |
| `ExportTextFilesTest` | drawtextへ渡す行ファイルの分け方（改行コード・空行） |
| `WaveformGeometryTest` | 波形のズーム範囲、ヒットテスト、クランプ、端スクロールのパンと刻み |
| `VideoMetadataReaderTest` | creation_time・ファイル名のパース |

`mockk` は `android.net.Uri` の差し替えにだけ使う。`org.json` は Android のスタブが
JVMで動かないため実装を入れている。

**instrumented テストと Compose UI テストは無い。** 再生・IME・書き出しの実挙動は
テストで守られていないので、この3つを触ったら実機で確認すること。

---

## 地雷リスト

- **R8**: `src/main/keepRules/rules.keep` の `com.arthenica.**` keep が必須。
  ffmpeg-kit はJNIからクラス名の文字列でJava側を呼び戻すため、外すと**ビルドは通るのに
  書き出しの瞬間に落ちる**。
- **ABI**: リリースは `abiFilters` で `armeabi-v7a` / `arm64-v8a` のみ（x86系81MBを削減）。
  debugは4ABIのままなのでエミュレータ検証はできる。`splits` は `shrinkResources` と競合するので使わない。
- **`@Preview` は1つも無い**。`ui-tooling-preview` の依存も外してある。プレビューを書くなら
  `implementation(libs.androidx.compose.ui.tooling.preview)` を足すこと
  （`debugImplementation` だとリリースビルドが通らなくなる）。
- **バックアップ**: `dataExtractionRules`(API 31+) と `fullBackupContent`(〜30) で全除外。
  保存しているURIは他端末では読めないため。`allowBackup="false"` 1つにまとめるのは
  Android 12以降で非推奨扱いになるので**しないこと**。
- **フォント/効果音は書き出しのたびに `filesDir` へ上書きコピーする**（意図的）。
  「あれば使い回す」にすると、assetsを差し替えても古い実体が使われ続ける。
- **ギャラリー画面は自前**。システムのフォトピッカーは返すURIがプロセス生存中しか
  有効でなく、アプリを閉じると編集の続きを復元できないため使っていない。
- **クリップタイルの `Modifier.animateItem()`**: タイムラインを丸ごと入れ替える
  （一時保存の読み出しと、その「もとに戻す」）と全クリップのidが一斉に変わり、
  消えるタイルのアニメーションが取り残されて**消えたはずのタイルが画面に残り続ける**
  （どこかに触れて再コンポーズが起きるまで消えない）。`TimelineStore.replacementCount`
  を見て `key()` でLazyRowごと作り直すことで避けている。1件ずつの追加・削除では
  この値を増やさない（増やすとフェードアウトまで消えてしまうため）。

---

- **FFmpeg は GPLv3**：`com.moizhassan.ffmpeg:ffmpeg-kit-16kb`（16KBページ対応のフォーク）は、POM では
  LGPLv3 と名乗っているが、中身は `--enable-gpl --enable-version3` でビルドされており、ライブラリ自身も
  「GPL version 3 or later」と名乗る（libx264 は入っておらず、書き出しは `h264_mediacodec` で行われる）。
  そのため**配布する APK は GPLv3**。アプリ内の表示は `ui/screens/LicenseDialog.kt`（保存ダイアログの
  「ライセンス」から開く）、全文は `assets/licenses/GPL-3.0.txt`、説明は README の「ライセンス」節。
  FFmpeg を差し替えたら、この3か所を合わせて見直すこと。
- **書き出しサービスの種類**：Android 15 以降は `mediaProcessing`、それより前は `dataSync`
  （`VlogExportService.foregroundServiceType()`）。マニフェストには両方の種類と権限を宣言してある。
  `dataSync` はデータの転送・同期向けで、Play の申告で用途が合わないと判断されうるので、全端末 `dataSync` に戻さないこと。

## 採った設計（似た形に戻さないために）

- **UI層へは値ではなく `StateFlow` を渡す。** `canUndo` や選択中の波形を「値」まで上げると、
  undo可否が変わったり波形が1本届いたりするたびに画面全体が再コンポーズされる。
  `StateFlow` のまま渡して collect は葉のComposableに残すと、依存だけが切れて
  再コンポーズ範囲は変わらない。操作は `@Stable` なホルダー（`TimelineActions`）に
  まとめ、`remember(viewModel)` で**1度だけ**作る（毎回作り直すと `@Stable` の意味が消える）。
- **波形は選択中の1本だけを流す**（`VlogViewModel.selectedWaveform`）。
  `Map<URI, Waveform?>` を画面へ渡すと、1本届くたびにMapが差し替わって全体が再コンポーズされる。
- **ViewModelは「クリップ一覧 vs 一時保存」ではなく「状態の層 vs その上の機能」で分ける。**
  一時保存の読み出しはクリップ一覧・再生・履歴を同時に触るため、一時保存だけを切り出そうと
  すると必ず失敗する。この3つを `TimelineStore` にまとめたことで、`ProjectsController` は
  その上に独立して載るようになった。

## 未解決 / 今後

- instrumented テストと Compose UI テストが無いのは変わっていない（「テスト」節を参照）。
  `ProjectsController` / `VlogViewModel` は JVM 単体テストで1件も守られていないので、
  触ったら実機で確認すること。`TimelineStore` は再生側を `edit/TimelinePlayback`
  （実装は `PlaybackController`）越しに受け取るようにしたので、偽物を渡してテストできる
  （`TimelineStoreTest`）。ただし偽物はプレイリストの中身と自動遷移を持たないので、
  並べ替え・削除・一時保存の読み出しとプレビューの同期は実機で確認すること。
