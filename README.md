# ドライブロガー（Drive Logger）

Android 用の、車での走行を GPS で記録するアプリのソースコードです。
必要なときだけドラレコのように録画したり、写真を撮ったり、車を停めて歩いたりと、1 回のドライブの中で自由に組み合わせられます。ナビ機能はありません。

- 説明・APK のダウンロード: <https://kijitora-no-hito.github.io/android-apps/drive_logger/>
- 設計: [docs/DESIGN.md](docs/DESIGN.md)

## ビルド

- Android Studio（JDK 17 以上）。`gradlew.bat test assembleDebug`（Windows）または `./gradlew test assembleDebug`。
- **Google Maps の API キーは自分で用意してください。** プロジェクトのルートの `local.properties` に
  `MAPS_API_KEY=...` と書きます（git 管理外）。無ければ地図は表示されません（`local.defaults.properties` のダミーが使われます）。
  キーには「Android アプリ」の制限（パッケージ名＋自分の署名証明書の SHA-1）と「Maps SDK for Android」だけの API 制限を掛けてください。
- release の署名は、ルートの `keystore.properties`（`storeFile` / `storePassword` / `keyAlias` / `keyPassword`）と鍵ファイルがあるときだけ行います（どちらも git 管理外）。
  無ければ release は署名なしでビルドされます。
- 自分で改造して配るときは、`applicationId`（`jp.kjtr.drivelogger`）と、更新の確認の取得先（`update/`）を自分のものに変えてください。

## 第三者のサービス・データ

画面の地図は Google マップ（Google の利用規約。Google マップは動画に入れません）、動画に入れる地図は国土地理院の地理院タイル（出典：国土地理院）を使います。
使っているライブラリはそれぞれのライセンスに従います（`gradle/libs.versions.toml`）。

## ライセンス

[MIT License](LICENSE)
