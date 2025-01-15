# MultiMicVideoRecorder
THINKLET向けの CameraX 録画アプリです．  
アプリを起動し，録画をすると，自動的にmp4ファイルを分割しつつ，長時間録画し続けることができます．  
また，同一ネットワーク配下にある場合，録画中の画角を他のデバイスのブラウザから確認できます．

## 導入
### はじめに
このアプリでは，録画ファイルの取り出しに，Adbコマンド，開発者画面の操作を使用しますので．  
まず，THINKLET開発者ポータルの[開発者画面を表示](https://fairydevicesrd.github.io/thinklet.app.developer/docs/startGuide/useCamera/), [adb設定](https://fairydevicesrd.github.io/thinklet.app.developer/docs/startGuide/helloworld#adb%E8%A8%AD%E5%AE%9A) を確認ください．

### ビルド
1. このレポジトリをcloneします．
2. ビルドするには，`local.properties` ファイルに以下を追記してください．
```
# GitHub Packages経由でライブラリを取得します．下記を参考にアクセストークンを発行ください．
# https://docs.github.com/ja/authentication/keeping-your-account-and-data-secure/creating-a-personal-access-token
# read:packages の権限が必須です
TOKEN=<github token>
USERNAME=<github username>
```
3. `gradlew` でのビルド (もしくは，AndroidStudioから実行してください．)
```bash
# デバッグインストール
./gradlew installDebug
# リリースビルド
./gradlew assembleRelease
```

### (オプション) 設定値の更新
ビルド前に， [app/build.gradle.kts](./app/build.gradle.kts) の以下の部分を編集して，  
機能のいくつかを無効化，設定値を変更できます．
```kotlin
// １ファイルあたりの動画の最大ファイルサイズ．最大は4GBです．
val fileSize = 1*1000*1000*1000 // = 1GB

// 録画に使うマイクのタイプ
// https://github.com/FairyDevicesRD/thinklet.camerax.mic で提供するマイクを切り替えます
//   5ch: シンプルな5chマイクを1chに変換したもの
//   xfe: AppSDKの試験的な音声処理を行った5chマイクを1chに変換したもの
//   normal: シンプルな1chマイクを使用したもの
val micType = "xfe" // or "5ch" or "xfe" or "normal"

// プレビューの有効化有無
val enablePreview = true

// Visionの有効化有無
// https://github.com/FairyDevicesRD/thinklet.camerax.vision で提供する
// THINKLET/Androidのカメラを別デバイスのブラウザから視る機能
val enableVision = true

// Visionのサーバーポート
val visionPort = 8080
```

## 使い方
> [!NOTE] 
> アプリ起動前に，事前にカメラとマイクのPermissionを許可してください．

### 基本的な使い方
1. 録画の開始と停止は，THINKLETの第2ボタン(CAMERAキー) で切り替えます．
2. 録画ファイルの取り出しは，`adb pull /sdcard/Android/data/com.example.fd.video.recorder/files/ /path/to/savedir` で行うことができます．
### オプション
#### Vision
1. アプリを実行するTHINKLETと，PCなどブラウザで閲覧するデバイスを同一のWi-Fiに接続してください．
2. THINKLETに割り振られた Wi-Fiを確認します．下記の場合，`192.168.0.123` となります．
```shell
$ adb shell ip addr show wlan0
22: wlan0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc mq state UP group default qlen 1000
    link/ether 30:**:**:**:**:** brd ff:ff:ff:ff:ff:ff
    inet 192.168.0.123/24 brd 192.168.0.255 scope global wlan0
# (略)
```
3. THINKLETで，本アプリを起動します．
4. 別デバイスのブラウザから，`http://192.168.0.84:8080` へアクセスします．`visionPort` を変えている場合は適宜読み替えてください．

