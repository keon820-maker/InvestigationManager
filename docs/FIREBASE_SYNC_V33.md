# v0.33 Firebase 동기화 설계

## 범위

- 한 사람이 같은 Google 계정으로 여러 Android 기기에서 사용한다.
- 조사 데이터는 Cloud Firestore, 사진·원본 파일은 Cloud Storage에 저장한다.
- 기기에는 Room DB와 원본 파일을 계속 유지하므로 네트워크가 없어도 기존 기능을 사용할 수 있다.
- 협업자 초대와 사용자 간 공유는 이 버전의 범위에 포함하지 않는다.

## 저장 경로

```text
Firestore
users/{firebaseUid}/cases/{caseCloudId}
users/{firebaseUid}/cases/{caseCloudId}/attachments/{attachmentCloudId}

Storage
users/{firebaseUid}/cases/{caseCloudId}/attachments/{attachmentCloudId}
```

`firestore.rules`와 `storage.rules`는 로그인한 UID가 경로의 UID와 같을 때만 읽기·쓰기를 허용한다. 다른 Google 계정은 데이터를 열 수 없다.

## 최초 연결

1. 기존 Room DB와 첨부 원본은 삭제하거나 초기화하지 않는다.
2. 기존 조사건과 첨부에 UUID 형식의 클라우드 ID를 부여한다.
3. 서버 데이터를 먼저 내려받아 비교한 뒤, 로컬에만 있는 항목을 올린다.
4. 첨부 원본은 업로드·다운로드 전후 SHA-256과 파일 크기를 확인한다.
5. 한 기기의 로컬 데이터는 최초 연결한 Firebase UID에 고정한다. 다른 계정으로 실수로 업로드하지 않는다.

첫 기기에서 동기화 완료를 확인한 다음 두 번째 기기에서 같은 Google 계정으로 로그인하는 순서를 권장한다.

## 충돌 정책

- 조사건마다 `updatedAt`과 `modifiedByDevice`를 저장한다.
- 두 기기에서 같은 조사건을 변경하면 더 최근 `updatedAt`이 우선한다.
- 밀리초까지 시간이 같은 경우 `modifiedByDevice`를 결정 기준으로 사용해 모든 기기가 같은 결과로 수렴하게 한다.
- 원본 파일은 자동 덮어쓰기하지 않는다. 저장된 SHA-256과 실제 파일이 다르면 동기화를 중단하고 오류를 표시한다.
- 기기 시간이 크게 틀리면 최신 판정도 틀릴 수 있으므로 Android의 자동 날짜/시간 사용을 권장한다.

## 삭제와 복구

- 삭제 버튼은 조사건과 첨부 원본을 즉시 지우지 않고 `deletedAt`이 있는 휴지통 항목으로 바꾼다.
- 휴지통 상태도 Firestore에 동기화되므로 오프라인 기기의 이전 사본이 조사건을 되살리지 않는다.
- 데이터 및 동기화 화면의 휴지통에서 조사건과 연결 원본을 복구할 수 있다.
- v0.33에서는 증거 원본의 우발적 소실을 막기 위해 자동 영구 삭제를 하지 않는다. 영구 삭제/보존기간 기능은 별도 확인 후 추가한다.

## Firebase 프로젝트 준비

1. Firebase 프로젝트를 만들고 Android 앱 `kr.co.investigation.manager`를 등록한다.
2. 영구 서명 인증서의 SHA-1 지문을 Android 앱 설정에 추가한다.
3. Authentication에서 Google 로그인 제공업체를 사용 설정한다.
4. Cloud Firestore와 Cloud Storage를 생성한다. 개인정보 저장 위치를 고려해 같은 리전을 선택한다.
5. 저장소의 `firestore.rules`와 `storage.rules`를 각각 배포한다.
6. Google 로그인을 사용 설정한 뒤 최신 `google-services.json`을 다시 다운로드한다.
7. 파일 전체를 Base64로 인코딩해 GitHub Repository secret `FIREBASE_GOOGLE_SERVICES_JSON_BASE64`에 저장한다.

GitHub Actions는 이 JSON을 저장소에 쓰지 않고 프로젝트 ID, 앱 ID, API 키, Storage bucket, Web OAuth client ID를 빌드 환경에만 전달한다. JSON이 없으면 빌드는 성공하지만 앱의 Google 로그인과 동기화는 비활성화된다.

## 개인정보 주의

Firebase 전송 구간과 저장소 암호화 및 UID 보안 규칙을 사용하지만, 이 구조는 사용자가 별도 암호를 보유하는 종단간 암호화 방식은 아니다. 실제 사용 전 Firebase 규칙 배포와 동일 계정 접근 확인이 필수다.
