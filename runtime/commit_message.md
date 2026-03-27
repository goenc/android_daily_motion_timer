固定音声によるフェーズ切替通知へ変更

・WalkingTimerService の TTS 経路を MediaPlayer と AudioFocusRequest を使う固定音声再生へ置換
・フェーズ復帰時の通知取りこぼし補完と停止時の再生解放を追加
・res/raw に fast_phase.mp3 と slow_phase.mp3 を追加
・JAVA_HOME を明示した assembleDebug 成功
・実機で前面 最小化 画面オフ 状態復帰時の AudioFocus 発火を確認
・実機のロック画面制約により 一時停止 再開 停止の UI 経由操作確認は未完了
