# 조사관리 Android 앱 (Galaxy Tab S11 / Galaxy Z Fold7)

업로드된 조사의뢰서 사진을 기준으로 만든 **실사용 지향 Android Studio 프로젝트 0.1**입니다.

## 구현된 핵심 기능

- 사진 선택 → 한국어 OCR(ML Kit) → 항목 자동 추출 → 사용자 검수/수정 → 저장
- 조사 목록 / 검색
- 넓은 화면(Tab S11, Fold7 펼침): **좌측 리스트 + 우측 지도**
- 좁은 화면(Fold7 접힘): **목록 / 지도 탭 자동 전환**
- `물건소재지`를 좌표로 변환하여 OpenStreetMap 지도에 마커 표시
- 상세 화면에서 조사 비고 기록
- 조사확인서 갤러리 첨부 및 카메라 촬영
- **원본 이미지 바이트 그대로 저장** (리사이즈/재압축 없음)
- 해상도 / 파일 크기 / EXIF 촬영일 / SHA-256 기록
- 조사의뢰서 화면 재생성 및 A4 PDF 생성
- 연도별 ZIP 내보내기
  - cases.json
  - attachments.json
  - 조사목록.csv
  - 모든 원본 사진
  - SHA256_MANIFEST.csv
  - ZIP 무결성 검증

## 중요: 원본 보존 정책

갤러리에서 가져온 파일은 InputStream → FileOutputStream으로 **바이트 그대로 복사**합니다. OCR용 처리는 원본 파일을 수정하지 않습니다. 카메라 촬영도 촬영 결과 파일을 원본으로 보관하며 앱에서 후처리/리사이즈하지 않습니다.

## 지도

지도 타일은 OpenStreetMap/Leaflet을 사용하므로 별도 Google Maps API Key가 필요 없습니다. 주소→좌표 변환은 Android `Geocoder`를 사용하므로 네트워크/기기 서비스 상태에 따라 실패할 수 있습니다. 실패한 주소는 상세 화면에서 주소를 수정한 뒤 저장하면 다시 좌표를 시도합니다.

## 빌드

1. Android Studio에서 이 폴더를 Open
2. JDK 17 이상 설정
3. Gradle Sync
4. Galaxy Z Fold7에서 USB 디버깅으로 테스트
5. Build > Generate App Bundles or APKs > APK

권장: minSdk 28, targetSdk 35. Tab S11 / Fold7는 동일 APK 사용.

## 0.1에서 추가 보완하면 좋은 항목

- 실제 귀사 문서 20~50장을 기반으로 OCR 좌표 영역 파서 고도화
- 지도 마커 클러스터링
- 아카이브 ZIP 가져오기/임시 열람
- PIN/생체인증 잠금 및 Android Keystore 기반 DB 암호화
- PDF 양식을 실제 원본 문서와 1:1 mm 단위로 최종 조정
- 임차인 1~10명 입력 전용 UI

## 개인정보 주의

이 앱은 주소/전화번호/주민번호 등 개인정보를 포함할 수 있으므로 실제 배포 전 앱 잠금, 암호화, 백업 정책을 확정하는 것을 권장합니다.
