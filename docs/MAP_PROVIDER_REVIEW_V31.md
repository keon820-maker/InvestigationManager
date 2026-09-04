# 지도 제공자 교체 검토 (v0.31)

## 현재 구성

- 화면 지도: `osmdroid` + OpenStreetMap 기본 타일(`MAPNIK`)
- 주소→좌표: Android `Geocoder`
- 실제 길안내: TMAP 또는 카카오맵 외부 앱

배경 지도와 주소 좌표 변환은 별개다. 지도 SDK만 교체해도 기존 마커 좌표는 계속 Android `Geocoder`가 만들기 때문에, 국내 주소 인식률까지 개선하려면 지도 SDK와 함께 해당 사업자의 지오코딩 API도 검토해야 한다.

## 후보 비교

| 후보 | 장점 | 제약/준비사항 | 현재 앱 적합성 |
|---|---|---|---|
| OpenStreetMap + osmdroid 유지 | API 키와 결제 계정이 필요 없고 현재 기능을 그대로 유지 | OSM 표준 타일 서버는 무료 데이터와 별개인 제한 자원이며 사용 정책과 캐시 정책을 지켜야 한다. 서비스 보장 없음 | 개인·소규모 사용에는 가장 간단함 |
| 카카오 Maps SDK v2 | 국내 지도 표기, 텍스트 라벨/오버레이, 카카오 길안내 흐름과 일관됨. 공식 안내상 무료 및 일 300,000회 | 카카오 개발자 앱 등록, Native App Key, 플랫폼/해시키 등록 필요. 주소 정확도 개선 시 Local API도 별도 연결 필요 | **교체 시 1순위 후보** |
| NAVER Map Android SDK | 국내용 지도 유형과 교통·대중교통 등 레이어 제공 | NAVER Cloud Platform Client ID 발급과 이용 설정 필요. 주소 정확도 개선 시 Geocoding API도 함께 구성 | 국내 현장 지도 대안으로 적합 |
| Google Maps SDK for Android | 안정적인 Android SDK, 전 세계 지도와 생태계 | 결제 계정 활성화와 API 키가 필수. 호출량에 따라 SKU 과금. 키 제한과 관리 필요 | 해외 확장 계획이 없다면 우선순위가 낮음 |

## 결론

v0.31에서는 OSM 지도를 유지하고 태블릿 레이아웃 경계와 폭 조절을 먼저 해결한다. 지도 교체는 화면 동작뿐 아니라 키 발급, 앱 서명 해시 등록, 지오코딩 변경, 호출 제한 및 장애 시 처리까지 함께 작업해야 한다.

다음 단계에서 교체한다면 카카오 Maps SDK v2 + 카카오 Local 주소검색 조합을 우선 시험한다. 현재 앱이 이미 카카오 길안내를 제공하므로 화면 지도와 길안내의 지명·좌표 체계를 맞추기 쉽다. 비교 테스트는 같은 실제 조사 주소 표본으로 Android Geocoder와 카카오 Local 결과를 대조한 뒤 결정한다.

## 공식 문서

- Kakao Maps SDK v2 Android: https://apis.map.kakao.com/android_v2/
- Kakao Maps SDK 시작하기: https://apis.map.kakao.com/android_v2/docs/getting-started/quickstart/
- NAVER Map Android SDK 시작하기: https://navermaps.github.io/android-map-sdk/guide-ko/1.html
- Google Maps SDK Android 사용량/결제: https://developers.google.com/maps/documentation/android-sdk/usage-and-billing
- OpenStreetMap 타일 사용 정책: https://operations.osmfoundation.org/policies/tiles/
