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
        minSdk = 24
        targetSdk = 37
        versionCode = 3
        versionName = "1.1"
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
        }
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
    implementation(libs.androidx.compose.ui.tooling.preview)
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

    // --- 開発用（デバッグビルドのみ。Layout Inspectorやプレビューで使う） ---
    debugImplementation(libs.androidx.compose.ui.tooling)

    // --- JVM単体テスト（src/test）。座標・区間まわりの純粋関数が対象 ---
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.org.json)
}