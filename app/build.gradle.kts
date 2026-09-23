import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // AGP 9系は Kotlin コンパイルを組み込みでサポートするため
    // kotlin-android プラグインは適用しない（適用すると競合する）
    alias(libs.plugins.kotlin.compose)
}

// リリース署名鍵の情報。パスワードを含むため keystore.properties は
// リポジトリに入れず（.gitignore参照）、各自のマシンにだけ置く。
// ファイルが無い場合はリリースビルドを未署名のまま進める
// （assembleRelease/bundleReleaseは成功するが、配布や実機インストールはできない）。
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.example.myvlogapp"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // Google Playは com.example.* を拒否するため、配布用の識別子にしてある。
        // 公開後は変更できない。namespace（Kotlinのパッケージ）とは別物なので、
        // ソースのpackage宣言は com.example.myvlogapp のまま据え置いている。
        // 変更前(com.example.myvlogapp)で入れたアプリとは別アプリ扱いになり、
        // 端末内の自動保存・一時保存は引き継がれない。
        applicationId = "com.masamune.myvlogapp"
        // Android 10 (API 29) 未満は切っている。ギャラリー保存に使う MediaStore の
        // RELATIVE_PATH + IS_PENDING、サムネイル取得の loadThumbnail、戻るジェスチャーの
        // 除外指定（systemGestureExclusionRects）がいずれもAPI 29からで、それ未満では
        // 「書き出した動画が保存できない・サムネイルが出ない」状態になるため。
        minSdk = 29
        targetSdk = 37
        versionCode = 4
        versionName = "1.3"

        // 画面操作のテスト（src/androidTest）。./gradlew connectedDebugAndroidTest で動かす
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            optimization {
                // R8による圧縮・難読化。dexが3.2MBまで縮み、未使用リソースも落ちる。
                //
                // ffmpeg-kitはJNIからクラス名の文字列でJava側を呼び戻すため、
                // src/main/keepRules/rules.keep の com.arthenica.** keepルールが必須。
                // これを外すと、書き出しの瞬間にネイティブ側が消えたクラスを
                // 呼びに行って落ちる（ビルドは通るので気付きにくい）。
                //
                // 有効化後、実機で書き出しが最後まで通ることを確認済み
                // （タイトルカードのフォント焼き込み、H.264+AAC出力、ギャラリー保存）。
                enable = true
            }

            ndk {
                // 配布物はarmの2種だけにする。ffmpeg-kitのネイティブライブラリは
                // 4ABIで約175MBあり、その約81MB（44%）がx86/x86_64だった。
                // x86のAndroid端末は事実上エミュレータとごく一部のChromebookだけで、
                // 配布先の実機では使われない。
                //
                // debugには掛けていないので、x86_64エミュレータでの動作確認は
                // これまでどおりできる（メモリ使用量の実測などはエミュレータで行っている）。
                //
                // splitsではなくabiFiltersを使うのは、splitsがリソース圧縮
                // （shrinkResources）と競合してエラーになるため。abiFiltersは
                // 「そもそも詰めないABIを決める」だけなので競合しない。
                abiFilters += listOf("armeabi-v7a", "arm64-v8a")
            }
        }
    }

    lint {
        // ChromeOsAbiSupport: リリースをarmの2ABIだけにしていることへの警告。
        // ChromeOSはx86バイナリが無くてもARMバイナリをバイナリトランスレータ経由で
        // 実行できる（lint自身の説明にもそう書かれている）ため、動かなくなるわけではない。
        // x86を積むとAPKが81MB増えるのに対し、得られるのは一部のChromeOSでの
        // 速度向上だけなので、このアプリでは割に合わないと判断して外している。
        disable += "ChromeOsAbiSupport"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // ABI分割（splits）は設定しない。AAB自体がABIごとの最適化を内包しており、
    // splitsを有効にするとリソース圧縮（shrinkResources）と競合してエラーになるため。

    packaging {
        jniLibs {
            // .soを圧縮せずAPKに格納する。16KBページサイズ対応の前提条件であり、
            // 実行時のネイティブライブラリ展開も不要になって起動が速くなる。
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // --- Compose ---
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.core.ktx)

    // --- Lifecycle / ViewModel ---
    // viewModel() と collectAsStateWithLifecycle() に必須
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // --- 動画プレビュー（書き出しはFFmpegが担当するのでtransformerは不要） ---
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)

    // --- 動画エンコード（16KBページサイズ対応フォーク） ---
    implementation(libs.ffmpeg.kit)

    // --- 開発用（デバッグビルドのみ。Layout Inspectorで使う） ---
    //
    // ui-tooling-preview（@Preview アノテーション）は入れていない。このアプリには
    // @Preview が1つも無いため。プレビューを書き始めるときは
    // implementation(libs.androidx.compose.ui.tooling.preview) を足すこと
    // （@Preview を main のソースに書くので、debugImplementation では
    // リリースビルドが通らなくなる）。
    debugImplementation(libs.androidx.compose.ui.tooling)

    // --- JVM単体テスト（src/test）。座標・区間まわりの純粋関数が対象 ---
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.org.json)

    // --- 画面操作のテスト（src/androidTest）。部品（Composable）を偽物の状態・操作で組み立てて確かめる ---
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // ui-test-junit4 が引き込む古い版（3.5.0）は Android 17 で全テストが落ちるので、新しい版に上げる
    androidTestImplementation(libs.androidx.test.espresso.core)
    // テスト用の空のActivity（ComponentActivity）をマニフェストへ足す。debugにしか入らない
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}