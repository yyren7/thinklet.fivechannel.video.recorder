# MultiMicCameraXRecorder
- THINKLETのマルチマイクを用いた録画サンプルアプリです．
- サンプルでは，サンプリングレート48kHz, チャンネル1ch, ビット深度16bit の設定で構成した`XFE`を用いて，録画を行います．
  - 変更するには `RecorderState.registerSurfaceProvider` 内の `mic` パラメーターを更新してください．(e.g. `ThinkletMics.FiveCh`)
- 録画の開始と停止は，THINKLETの第2ボタン(CAMERAキー) で切り替えます．
- 録画ファイルは，`/sdcard/Android/data/com.example.fd.camerax.recorder/files/` 以下にmp4形式で保存します．

## ビルド
- ビルドするには，`local.properties` ファイルに以下を追記してください．

```
# GitHub Packages経由でライブラリを取得します．下記を参考にアクセストークンを発行ください．
# https://docs.github.com/ja/authentication/keeping-your-account-and-data-secure/creating-a-personal-access-token
# read:packages の権限が必須です
TOKEN=<github token>
USERNAME=<github username>
```

- `gradlew` でのビルド

```bash
# デバッグインストール
./gradlew installDebug
# リリースビルド
./gradlew assembleRelease
```

> [!NOTE] 
> アプリ起動前に，事前にカメラとマイクのPermissionを許可してください．
