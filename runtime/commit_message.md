タイマー進行と音声処理の実行基盤を分離

・WalkingTimerService の ticker を専用単一スレッドへ移し フェーズ遷移通知を別ジョブへ分離
・PhaseAudioPlayer を HandlerThread と prepareAsync ベースへ変更し 再試行と AudioFocus 処理を専用スレッドへ集約
・JAVA_HOME にローカル Android Studio JBR を設定して assembleDebug 成功を確認
