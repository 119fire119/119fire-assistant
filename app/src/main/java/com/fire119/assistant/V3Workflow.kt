package com.fire119.assistant

/**
 * V3 업무 흐름의 공통 규칙입니다. UI 문구와 상태 판정이 서로 달라지지 않도록
 * Kotlin 한 곳에서 관리합니다. 자동화는 후보만 만들고, 중요한 상태 변경은 화면에서
 * 확인받는 원칙을 지킵니다.
 */
object V3Workflow {
    const val NEW_INQUIRY = "신규문의"
    const val SITE_CHECK = "현장확인대기"
    const val QUOTE_WAIT = "견적대기"
    const val QUOTE_SENT = "견적발송"
    const val WORK_SCHEDULED = "공사예정"
    const val WORKING = "공사중"
    const val WORK_COMPLETE = "공사완료"
    const val PAID = "수금완료"
    const val HOLD = "보류"
    const val LOST = "실주"
    const val UNKNOWN = "결과 확인 불가"

    @JvmStatic
    fun normalizeStatus(raw: String?): String = when (raw?.trim()) {
        NEW_INQUIRY, SITE_CHECK, QUOTE_WAIT, QUOTE_SENT, WORK_SCHEDULED,
        WORKING, WORK_COMPLETE, PAID, HOLD, LOST, UNKNOWN -> raw.trim()
        else -> NEW_INQUIRY
    }

    @JvmStatic
    fun closeMessage(afterPhotoCount: Int, workNote: String?): String = when {
        afterPhotoCount == 0 -> "공사 후 사진이 없습니다. 그래도 완료하시겠습니까?"
        workNote.isNullOrBlank() -> "작업기록이 비어 있습니다. 실제 작업내용을 먼저 확인해주세요."
        else -> "사진과 작업기록을 확인했습니다. 이 현장을 공사완료 처리할까요?"
    }

    @JvmStatic
    fun blogReadiness(before: Int, during: Int, after: Int, note: String?): String = when {
        note.isNullOrBlank() -> "작업내용 부족"
        before == 0 || after == 0 -> "사진 부족"
        else -> "블로그 작성 가능"
    }
}
