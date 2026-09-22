# Brush Alarm

양치 동작이 **30초 동안 감지되어야 멈추는 Android 알람 앱**입니다.

[![Android CI](https://github.com/JaeoneLim/brush-alarm/actions/workflows/android.yml/badge.svg)](https://github.com/JaeoneLim/brush-alarm/actions/workflows/android.yml)
[![Latest release](https://img.shields.io/github/v/release/JaeoneLim/brush-alarm?display_name=tag)](https://github.com/JaeoneLim/brush-alarm/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

<p align="center">
  <img src="docs/images/app-preview.png" width="100%" alt="Brush Alarm의 시간 설정 화면, 실시간 양치 감지 화면, 동작 흐름 예시">
</p>

> 위 이미지는 동작을 설명하기 위한 UI 예시입니다. 글꼴과 시스템 권한 화면은 기기에 따라 달라질 수 있습니다.

## 동작 방식

1. 지정한 시각에 전체 화면 알람, 반복음, 진동을 시작합니다.
2. 전면 카메라에서 얼굴과 입 주변 움직임을 기기 내에서 분석합니다.
3. 유효한 양치 동작이 감지되는 동안만 진행 시간을 누적합니다.
4. 누적 시간이 30초에 도달하면 알람을 종료하고 기존 알람 음량을 복원합니다.

양치 화면에서는 뒤로가기와 음량 버튼을 차단합니다. 알람이 울리는 동안 시스템 알람 음량을 최대로 유지하며, 외부에서 음량을 낮추더라도 다시 최대로 복구합니다.

## 다운로드 및 설치

최신 APK는 [GitHub Releases](https://github.com/JaeoneLim/brush-alarm/releases/latest)에서 받을 수 있습니다.

- Android 12 이상
- ARM64 Android 기기
- Google Play 서비스 필요
- 최초 얼굴 감지 모델 준비 시 네트워크 연결이 필요할 수 있음
- 처음 설치할 때 출처를 알 수 없는 앱 설치 허용 필요

설치 후 앱의 **필수 시스템 권한 확인**을 눌러 다음 항목을 모두 허용하세요.

- 카메라
- 알림
- 정확한 알람
- 전체 화면 알람

## 개인정보 보호

- 카메라 프레임은 기기 안에서만 실시간 분석합니다.
- 영상을 녹화하거나 파일로 저장하지 않습니다.
- 영상 및 분석 데이터를 서버로 전송하지 않습니다.

## Android 제한 사항

일반 Android 앱은 운영체제 수준의 다음 동작까지 막을 수 없습니다.

- 설정에서 강제 종료
- 앱 삭제 또는 권한 철회
- 기기 전원 종료 및 재부팅
- 제조사별 절전 정책과 고정 음량 기기의 제한

이 저장소의 앱은 일반 개인폰에서 허용되는 범위 안에서 전체 화면 알람, 포그라운드 서비스, 뒤로가기 차단, 음량 버튼 차단 및 최대 알람 음량 유지를 적용합니다.

## 개발

JDK 17과 Android SDK 35가 필요합니다.

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

GitHub Actions:

- `Android CI`: push와 pull request에서 테스트, Lint, debug APK 빌드
- `Publish Android Release`: `v*` 태그에서 테스트 후 서명된 release APK와 SHA-256 체크섬 게시

## 라이선스

[MIT License](LICENSE)
