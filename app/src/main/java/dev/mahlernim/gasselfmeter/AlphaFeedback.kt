package dev.mahlernim.gasselfmeter

import android.content.Context
import android.content.Intent

/** Opens a draft in the user's chosen app. The user reviews and sends it. */
internal object AlphaFeedback {
    fun draft(summary: String) = """
        똑똑 자가검침 AI 알파 피드백
        앱 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android ${android.os.Build.VERSION.SDK_INT}

        $summary

        공식 화면과 비교한 결과
        기대한 동작
        실제 동작

        고객번호, 주소, 비밀번호와 인증번호는 적지 말아 주세요.
    """.trimIndent()

    fun share(context: Context, summary: String) {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "똑똑 자가검침 AI ${BuildConfig.VERSION_NAME} 알파 피드백")
            .putExtra(Intent.EXTRA_TEXT, draft(summary))
        context.startActivity(Intent.createChooser(intent, "피드백 초안 보내기"))
    }
}
