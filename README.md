# 119파이어 하이브리드 APK V1

이 프로젝트는 **HTML 화면 + Android 네이티브 기능**을 합친 샘플입니다.

## 이번 버전에서 실제로 되는 것
- APK 안에 HTML 업무 화면을 포함해서 앱처럼 실행
- 앱에서 현장명을 지정하고 카메라 촬영
- 사진을 `Pictures/119Fire/<현장명>/` 앱 전용 영역에 저장
- 삼성 전화앱 등이 만든 통화녹음 폴더를 사용자가 직접 연결
- 최근 통화녹음 파일 최대 30개 읽기
- 백그라운드에서 약 15분 단위로 새 녹음파일 확인 후 Android 알림
- Netlify 서버를 통한 OpenAI 업무비서
- 5MB 이하 통화녹음 파일을 서버에 보내 전사 + AI 요약
- OpenAI API Key는 APK에 넣지 않고 서버 환경변수에만 저장
- GitHub Actions로 APK 자동 빌드

## 중요한 제한
- 앱 자체가 전화 통화를 녹음하지 않습니다. 갤럭시 기본 통화녹음 파일을 사용합니다.
- Android WorkManager의 주기 작업은 정확히 15분마다 실행된다고 보장되지 않습니다. 시스템 배터리 정책에 따라 늦어질 수 있습니다.
- 자동 감지는 새 녹음 존재를 알리는 기능입니다. 사용자가 누르기 전에는 녹음파일을 자동으로 AI 서버에 업로드하지 않습니다.
- 전사 업로드는 샘플 안전장치로 5MB 이하만 허용합니다.
- 고객 자동 매칭은 아직 샘플 단계입니다. 통화 파일명 형식이 기기/삼성 앱 버전에 따라 달라질 수 있어 실제 폰에서 형식을 확인한 뒤 맞추는 것이 안전합니다.

## APK 만들기
이 프로젝트를 GitHub 저장소에 올리면:
Actions → Build 119Fire APK → Artifacts → `119fire-hybrid-apk`
에서 APK를 받을 수 있습니다.

## AI 서버 배포
`server/` 폴더는 Netlify용입니다.

Netlify 환경변수:
- `OPENAI_API_KEY` : 본인의 OpenAI API Key
- `APP_ACCESS_CODE` : 본인이 정한 긴 비밀 코드
- `OPENAI_MODEL` : 기본 `gpt-5.6-luna`
- `TRANSCRIBE_MODEL` : 기본 `gpt-4o-mini-transcribe`

배포 후 APK → 설정에서
- Netlify 서버 주소
- APP_ACCESS_CODE
를 입력합니다.

**OpenAI API Key는 APK 설정 화면에 입력하지 않습니다.**

## 다음 단계
실제 갤럭시에 샘플 APK를 설치한 다음,
1. 통화녹음 파일명이 어떤 형식인지
2. 삼성 녹음 폴더가 어디인지
3. 전화번호가 파일명에 들어가는지
를 확인하면 기존 고객 자동 매칭 정확도를 높일 수 있습니다.
