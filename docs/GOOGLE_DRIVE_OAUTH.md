# 119파이어 V3 Google Drive 최초 연결

이 과정은 사진·견적서·문서 원본을 Google Drive에 보관하기 위한 **최초 1회 관리자 설정**입니다. Google 비밀번호, OpenAI API Key, Drive 파일을 APK 안에 넣지 않습니다.

## V3가 건드리지 않는 기존 구조

현재 사용 중인 `2024년도`, `2025년도`, `2026년도`, `계산기`, `자금관리`, `근로자계약서`, `기타서류`, `인증서`, `정산폴더`, `blog`, `GPT`는 이동·삭제하지 않습니다.

신규 현장만 기존 방식에 맞춰 다음 후보를 사용합니다.

```
2026년도 / 10월 / 현장명
```

동일 또는 비슷한 현장명 폴더를 먼저 검색하고, 하나로 확정되지 않으면 `신규 폴더 생성 확인`으로 보냅니다.

## 필요한 Google Cloud 설정

1. Google Cloud Console에서 119파이어 전용 프로젝트를 선택하거나 만듭니다.
2. **Google Drive API**를 사용 설정합니다.
3. OAuth 동의 화면을 `테스트 사용자`로 구성하고, 본인 Google 계정을 추가합니다.
4. 서버용 OAuth 2.0 클라이언트를 만들고, Netlify 콜백 주소를 승인된 리디렉션 URI로 등록합니다.
5. 본인 계정으로 한 번 승인해 Drive 전용 refresh token을 발급합니다.
6. Netlify 환경변수에 다음만 저장합니다.

| 환경변수 | 용도 |
| --- | --- |
| `GOOGLE_DRIVE_CLIENT_ID` | OAuth 클라이언트 ID |
| `GOOGLE_DRIVE_CLIENT_SECRET` | OAuth 클라이언트 비밀 |
| `GOOGLE_DRIVE_REFRESH_TOKEN` | 본인 Drive 접근 갱신 토큰 |
| `GOOGLE_DRIVE_ROOT_FOLDER_ID` | 선택: 119파이어 업무 폴더의 상위 ID |

`OPENAI_API_KEY`, `APP_ACCESS_CODE`는 기존 Netlify 환경변수를 계속 사용합니다. 비밀값은 앱 코드, GitHub, README에 넣지 않습니다.

## 승인 후 앱이 하는 일

- 기존 연도·월·현장 폴더를 먼저 검색
- 같은 이름이 하나면 기존 폴더 연결
- 확실한 신규 현장만 폴더 생성 후보 작성
- 사진 해시/Drive 파일 ID로 중복 업로드 방지
- 업로드 성공 뒤 Room에는 Drive 파일 ID, 현장 폴더 ID, 동기화 시각만 인덱스로 저장

OAuth 설정 전에는 앱이 `Drive 업로드 대기`로만 표시합니다. 실제 업로드 완료로 바꾸지 않습니다.
