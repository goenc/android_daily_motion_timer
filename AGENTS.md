# Agent Definition

* 共通方針は `C:\Users\gonec\AndroidProjects\AGENTS.md` を参照する

## Git 運用

* 作業完了時は **必ず** git commit と git push を実行する
* 変更がある限り、コミットとプッシュまで到達して初めて作業完了とする
* 利用者が明示的に「コミットしない」と指示した場合のみ git commit を省略する
* 利用者が明示的に「プッシュしない」と指示した場合のみ git push を省略する
* コミットメッセージのタイトル・本文は日本語で書く
* 作業 branch は `work` を使用する（`main` では commit しない）
* upstream 未設定時は `git push -u origin HEAD` を使う
