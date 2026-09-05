# Security and privacy

이 저장소의 앱은 조사 업무에서 주소, 전화번호, 주민등록번호, 원본 문서 사진 등 민감한 개인정보를 처리할 수 있습니다.

## 저장소에 올리면 안 되는 자료

- 실제 조사의뢰서/조사확인서 사진
- 실제 고객·채무자·소유자·임차인 데이터
- 앱에서 내보낸 연도별 ZIP/CSV/JSON/DB
- 서명키(`*.jks`, `*.keystore`)와 비밀번호/토큰/API 키
- `local.properties`, `keystore.properties`

## 앱의 현재 보호 원칙

- 원본 증거 이미지는 리사이즈/재압축하지 않습니다.
- 앱 백업은 비활성화합니다.
- 평문(cleartext) 네트워크 트래픽은 허용하지 않습니다.
- FileProvider는 앱의 `originals/` 디렉터리만 외부 카메라 앱에 제한적으로 공유합니다.
- 원본 파일은 SHA-256 해시를 기록합니다.
- Firebase 동기화는 로그인 UID별 Firestore/Storage 경로로 분리하며 다른 UID 접근을 규칙으로 차단합니다.
- 첨부 원본은 업로드와 다운로드 시 SHA-256 및 파일 크기를 검증합니다.
- 기기 로컬 데이터는 최초 연결한 Firebase UID에 고정해 다른 계정으로 잘못 업로드하지 않습니다.
- 삭제는 우선 동기화 가능한 휴지통 표시로 처리해 오프라인 기기의 오래된 사본이 다시 나타나지 않게 합니다.

## Firebase 운영 주의

- `firestore.rules`와 `storage.rules`를 실제 Firebase 프로젝트에 배포하기 전에는 실데이터를 동기화하지 않습니다.
- `google-services.json`은 저장소에 커밋하지 않고 Base64 GitHub Repository secret으로만 전달합니다.
- Firebase API 키와 앱 설정은 최종 APK에서 추출될 수 있으므로 비밀로 간주해 접근을 통제할 수 없습니다. 실제 데이터 보호는 Firebase Authentication과 보안 규칙에 의존합니다.
- Firebase 기본 전송/저장 암호화를 사용하지만 별도 사용자 암호 기반 종단간 암호화는 아닙니다.

공개 저장소로 전환하기 전에는 Git 커밋 메타데이터와 과거 브랜치에도 개인정보나 비밀정보가 없는지 별도로 확인해야 합니다.
