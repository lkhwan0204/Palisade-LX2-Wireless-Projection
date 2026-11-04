# 팰리세이드 LX2 무선 Apple CarPlay / Android Auto

**한국어** | [English](../README_EN.md)

## 다운로드 — 공개 실험판

[실험판 ZIP 다운로드](https://github.com/lkhwan0204/Palisade-LX2-Wireless-Projection/releases/download/v2.1.4-lx2dev.12-noaa-tbt.1/wireless-carplay-aa-lx2dev-2.1.4-lx2dev.12-noaa-tbt.1.zip) · [SHA256SUMS](https://github.com/lkhwan0204/Palisade-LX2-Wireless-Projection/releases/download/v2.1.4-lx2dev.12-noaa-tbt.1/SHA256SUMS) · [Pre-release 설명](https://github.com/lkhwan0204/Palisade-LX2-Wireless-Projection/releases/tag/v2.1.4-lx2dev.12-noaa-tbt.1)

SHA-256: `ff99564ae90cb650beda6581b270893ff3d370d48cd2131619f24f9371b36749`

> ⚠️ **이 실험판은 실차 미검증이며 설치를 권장하지 않습니다.** 시험에 쓰인 정확한 HMG-API 빌드·LX2 후크 맵과 제거·복구 경로가 확인되지 않았습니다. 파일 다운로드가 설치 안전성이나 기능 작동을 뜻하지 않습니다.
>
> 공개된 `2.1.4-lx2dev.12-noaa-tbt.1`은 실차 성공이 보고된 원본 `2.1.3-lx2dev.12`와 **다른 빌드**입니다. Android Auto의 계기판·HUD 길안내를 제외했으며 CarPlay와 Android Auto의 기본 연결도 이 실험판에서는 실차 검증되지 않았습니다. 기존 2.1.3 위에 설치하면 버전 상승으로 이전 ZIP으로의 다운그레이드가 거부될 수 있습니다.

> ⚠️ **비공식 실험용 프로젝트 — 설치 전에 읽어 주세요**
>
> 이 프로젝트는 현대자동차, 기아 또는 다른 자동차 제조사와 제휴하지 않은 독립 프로젝트이며 제조사의 승인이나 보증을 받지 않았습니다. 설치와 사용은 자발적인 선택이며 사용자 자신의 판단과 책임하에 이루어집니다.
>
> 인포테인먼트 오작동, 화면 멈춤·검은 화면, 부팅 실패, 데이터 손실, 배터리 방전, 차량 기능 장애, 소프트웨어 업데이트 충돌, 보증이나 서비스에 대한 영향이 생길 수 있습니다. 발생 여부와 범위는 차량과 상황에 따라 다릅니다.
>
> 소프트웨어는 상품성, 특정 목적 적합성 및 오류 없는 작동을 포함한 어떠한 명시적·묵시적 보증 없이 **있는 그대로(AS IS)** 제공됩니다. 관련 법률이 허용하는 최대 범위에서 개발자와 기여자는 설치 또는 사용으로 인한 손해에 대한 책임을 지지 않습니다. 이 문구는 법률상 배제할 수 없는 권리나 책임을 제한하지 않습니다.
>
> **주행 중 설치·조작·시험하지 마세요.** 안전한 장소에 주차하고 전원 공급과 환기를 확보하세요. 시작 전에 필요한 데이터의 백업과 실제 사용할 수 있는 복구 수단을 준비하세요.

[English](../README_EN.md) · [호환성](COMPATIBILITY.md) · [시험 결과](TEST_RESULTS.md) · [문제 해결](TROUBLESHOOTING.md)

## 현재 상태 — 공개 실험판과 원본

위 실험판 ZIP과 체크섬은 Pre-release에서 내려받을 수 있습니다. 원본 실차 성공 보고 대상 `wireless-carplay-aa-lx2dev-2.1.3-lx2dev.12.zip`은 배포 보류 상태이며, 공개 실험판과 다릅니다. 공개 실험판의 대응 소스와 고지는 [배포 검토](DISTRIBUTION.md)를 참조하세요. **실차 작동 및 복구 경로가 검증되기 전에는 설치를 권장하지 않습니다.**

## 실차 시험 범위

사용자 보고에 따른 단일 차량의 제한적인 시험입니다. 개발 산출물의 최초 설명은 제작 당시 ‘차량 미검증’으로 기록되어 있으며, 아래는 이후 제공된 시험 결과입니다. 이번 문서 작업에서 직접 차량을 시험하거나 로그로 재확인하지 않았습니다.

| 항목 | 시험 환경 / 결과 |
|---|---|
| 차량 | 2021 Hyundai Palisade LX2, 한국형, Gen5W, Android 4.4.2 |
| 펌웨어 | `LX2.KOR.0000.V039.001.260902` |
| HMG-API | `1.0.0-beta.6` |
| NEMESIS | 이전 기록은 1.15; **최종 시험 당시 버전은 미확인** |
| 모드 | `2.1.3-lx2dev.12` |
| 결과 | 무선 Apple CarPlay와 무선 Android Auto 연결, 지도·음악·터치 동작 보고 |

Android Auto에서 CarPlay로 전환할 때 처음 검은 화면이 발생했고, Bluetooth 재연결과 화면 재진입 후 정상으로 돌아왔다고 보고되었습니다. 원인과 재현 조건은 미확인입니다. 장시간 안정성, 다른 차량·펌웨어·폰, 현재 공식 HMG-API와 NEMESIS의 조합은 시험되지 않았습니다. 단일 성공 사례는 호환성이나 안전성을 보장하지 않습니다.

**버전 선택:** beta.6은 보고된 시험 버전이지 지금 설치를 권장하거나 제공하는 버전이 아닙니다. 현재 공식 HMG-API의 NEMESIS Package Store 경로는 NEMESIS **1.16 이상**이 필요합니다. 이는 현재 공식 설치 요건이며 시험 차량의 최종 NEMESIS 버전이나 현재 버전 조합의 실차 검증을 의미하지 않습니다. 정확한 조합과 LX2 후크 맵·복구 경로가 검증될 때까지 새 설치를 중단하세요.

[한국어 상세 설치 안내](INSTALLATION_KO.md) · [English installation guide](INSTALLATION_EN.md)

## 설치 안내: NEMESIS → HMG-API → LX2 모드

**아래는 공식 문서에 근거한 절차 설명입니다. 공개 실험판은 실차 미검증이며 정확한 호환 HMG-API 빌드, 재현 가능한 후크 맵 설치 및 복구 경로가 확인되지 않았으므로 실제 설치를 권장하지 않습니다.** 버전마다 메뉴와 설치 경로가 달라질 수 있습니다.

### 1. 차량과 준비물 확인

1. 차량 설정의 일반 > 소프트웨어 정보에서 차종, 지역과 펌웨어를 위 표와 비교합니다. 다르면 지원 여부가 확인되지 않았으므로 중단합니다.
2. 공식 [NEMESIS 호환성 안내](https://github.com/xwtk/hmg_nemesis#compatibility-check)를 읽습니다. Android 기반 Gen5W의 `S5W_M`과 실제 OTA 업데이트 지원이 필요하며, Engineering Mode에 Update OTA 항목이 보이는 것만으로 OTA 지원이 증명되지는 않습니다. 공식 문서는 일부 차종이 이 조건을 갖춰도 동작하지 않을 수 있다고 설명합니다.
3. 컴퓨터, 빈 USB, 안정적인 전원, 백업과 복구 방법을 준비합니다. 포맷은 USB의 데이터를 지웁니다. 호환성이나 복구 방법을 확신할 수 없으면 중단합니다.

### USB 구성 미리 보기

각 단계는 서로 다른 USB 구성입니다. 같은 USB를 재사용한다면 이전 단계 파일을 정리하고 해당 단계의 공식 안내에 따라 준비하세요. `USB/`는 USB 드라이브의 최상위라는 뜻입니다.

**NEMESIS 초기 설치** — TAR 파일을 그대로 복사:

```text
USB/
└── ota_package.tar
```

**HMG-API의 NEMESIS Package Store 방식** — 해당 ZIP을 USB 최상위에 압축 해제:

```text
USB/
├── NEMESIS.data
├── packages/
│   └── … 공식 패키지 파일 …
└── README …
```

**모드 설치** — 공개 실험판 ZIP을 압축 해제하지 않고 복사 (설치 비권장):

```text
USB/
└── HMGAPI/
    └── updates/
        └── wireless-carplay-aa-lx2dev-2.1.4-lx2dev.12-noaa-tbt.1.zip
```

HMG-API의 **직접 USB 방식**은 별도의 `hmgapi-<version>-usb.zip`을 사용하며, 해당 버전 ZIP의 README대로 USB 최상위에 압축을 풉니다. 위 NEMESIS 패키지 구조로 가정하지 마세요.

### 2. 공식 NEMESIS 다운로드 및 설치

1. [NEMESIS 공식 GitHub](https://github.com/xwtk/hmg_nemesis)에서 설치 안내와 `ota_package.tar` 링크를 확인하고 공식 파일을 받습니다. [상세 설치 문서](https://github.com/xwtk/hmg_nemesis/blob/main/03-Install-and-Uninstall.md)도 확인하세요.
2. 빈 USB를 FAT32 또는 exFAT로 포맷하고 `ota_package.tar`를 **압축을 풀거나 다시 압축하지 않고 USB 최상위**에 복사합니다.
3. 주차 상태에서 차량 USB 포트에 꽂고, 해당 장치의 공식 안내에 따라 Engineering Mode > USB Copy > Update DSP > Update OTA로 들어갑니다. 진입 방법은 장치마다 다르므로 비밀번호나 코드를 추측하지 마세요. 파일을 지원하지 않는다는 일반 USB 메시지가 보일 수 있습니다.
4. 공식 안내대로 설치가 완료되고 헤드유닛이 재시작할 때까지 기다립니다. 재시작 후 NEMESIS Manager가 열리는지와 버전을 확인합니다. 설치 진행이 확인되지 않거나 오류·재부팅 반복이 발생하면 계속 시도하지 말고 중단해 공식 문제 해결 안내를 따르세요.
5. 현재 공식 문서에서는 Manager에서 설정된 물리 버튼을 세 번 눌러 Manager를 여는 방법을 설명합니다. 신규 설치 기본 버튼은 Navigation으로 기재되어 있으나 버전·설정에 따라 다를 수 있습니다. [공식 Manager 안내](https://github.com/xwtk/hmg_nemesis/blob/main/02-Debugging-Execution-Interface.md)를 먼저 확인하고 현재 버전과 선택된 버튼을 모르면 추측하지 마세요. Manager의 버전 표시 또는 해당 버전의 공식 안내로 버전을 확인하세요.
6. NEMESIS 1.15 이하는 현재 공식 README에서 1.16으로 올릴 때 초기 설치 절차를 다시 사용하도록 안내합니다. 1.16 이상은 공식 업데이트 파일을 이용하는 별도 방법이 있습니다. [현재 공식 업데이트 설명](https://github.com/xwtk/hmg_nemesis#updating-nemesis)을 확인하세요. **업데이트 자체가 이 프로젝트의 호환성을 입증하지 않습니다.**

### 3. 공식 HMG-API 설치와 후크 확인

충돌 가능 패키지를 먼저 확인하세요. 공식 안내는 HMG Leafia Plus (`wireless.carplay.aa`), `carplay.session`, `voicehandler.vr-gates`와 HMG-API의 충돌, 그리고 `wireless.projection`, `projection.tbt`와 무선 프로젝션 모드의 충돌을 명시합니다. 기존 설정·복구 방법을 확보하고 [공식 충돌 목록](https://github.com/VysseCodes/HMG-API/wiki/Installing-HMG-API)을 확인하세요.

- **NEMESIS 1.16 이상:** [HMG-API 공식 Releases](https://github.com/VysseCodes/HMG-API/releases)에서 설치 대상 버전의 `hmgapi-<version>-nemesis.zip`을 받습니다. 공식 [NEMESIS 설치 안내](https://github.com/VysseCodes/HMG-API/wiki/Installing-HMG-API#nemesis)에 따라 빈 FAT32/exFAT USB 최상위에 압축을 풀어 `NEMESIS.data`, `packages/`, README가 놓이게 합니다. Android가 켜진 후 꽂고 Manager의 Package Store에서 HMG-API를 설치합니다. 안내에 따라 재시작하고 USB를 제거합니다.
- **NEMESIS 1.15 등에서 Package Store 경로를 쓸 수 없는 경우:** 현재 공식 [USB 설치 안내](https://github.com/VysseCodes/HMG-API/wiki/Installing-HMG-API#usb-stick)는 별도의 `hmgapi-<version>-usb.zip`을 빈 FAT32 USB 최상위에 압축 해제하고 Engineering Mode > USB Copy > DSP > Update OTA를 사용하는 경로를 설명합니다. 설치 문구와 재시작을 기다리고 USB를 제거하며 `HMGAPI/install.log`를 확인합니다. 공식 문서는 이후 OTA 화면의 오류 684가 예상될 수 있다고 설명합니다. **이 경로 또는 최신 HMG-API와 lx2dev.12의 조합은 이 프로젝트에서 실차 검증되지 않았습니다.** 확인되지 않은 설치를 계속하지 마세요.

앱 메뉴에서 HMG-API를 실행합니다. 타일이 없다면 현재 공식 문서의 대안은 NEMESIS Manager > My Packages > HMG-API > Launch입니다. 앱 상단에서 **Hooks on**과 맵 검사 상태를 확인합니다. ‘Hooks off’, 맵 미확인, 미지원, 부팅 가드 또는 오류가 나타나면 모드 설치를 중단합니다. `Hooks on`은 모든 기능의 성공을 보증하지 않습니다. 실차 시험에 사용된 정확한 맵과 공식 현재 버전의 LX2 호환성은 아직 확보되지 않았습니다. 강제 설치나 맵 검사 우회는 하지 마세요.

### 4. LX2 모드 설치 — 실차 검증 및 복구 경로 확인 후에만

1. 위 공개 실험판 ZIP과 [SHA256SUMS](https://github.com/lkhwan0204/Palisade-LX2-Wireless-Projection/releases/download/v2.1.4-lx2dev.12-noaa-tbt.1/SHA256SUMS)를 내려받아 체크섬을 확인합니다. **다운로드는 가능하지만 실차 미검증 및 복구 경로 미확인으로 설치를 권장하지 않습니다.**
2. USB 최상위에 `HMGAPI/updates/` 폴더를 만들고 ZIP을 압축 해제하지 않은 채 복사합니다. HMG-API가 서명과 파일 무결성을 검사하므로 ZIP 내부를 수정하거나 다시 압축하지 않습니다.
3. 차량에서 HMG-API > **Check USB updates**를 열고 모드를 선택합니다. 작성자·서명·버전·차량 검사 결과를 확인합니다. HMG-API는 처음 보는 작성자에 대해 경고할 수 있으며 이후 업데이트는 동일한 작성자 키가 필요합니다. 신뢰하지 않는 작성자나 호환성 거부가 나오면 중단하세요.
4. 설치 후 메인 화면에서 **Apply changes**를 누르고 적용 계획과 오류 여부를 확인합니다. 안내에 따라 재시작하고 USB를 뺍니다.
5. **Mods**에서 모드가 표시되고 활성화되어 있는지 확인합니다. 현재 공식 앱 안내에서는 녹색은 실행, 노란색은 충돌, 빨간색은 미실행 또는 오류, 회색은 꺼짐을 뜻합니다. 화면은 버전에 따라 다를 수 있습니다. ‘Changes not applied’ 또는 ‘Restart needed’가 남으면 상태를 확인하고 안내를 따르세요. [공식 모드 안내](https://github.com/VysseCodes/HMG-API/wiki/Installing-and-removing-mods).
6. 주차 상태에서 iPhone의 Bluetooth와 Wi-Fi를 켜고 차량 전화/프로젝션 설정에서 페어링한 뒤 CarPlay 요청을 허용합니다. Android는 Bluetooth 및 Wi-Fi를 켜고 차량의 Android Auto 페어링 안내를 따릅니다. USB 메모리나 유선 연결이 무선 연결을 방해할 수 있습니다. 화면·오디오·터치를 주차 중 확인하세요.

### 5. 휴대폰 최초 연결 — 실차 호환성과 복구 경로 검증 후

**iPhone**

1. 주차한 상태에서 iPhone의 Wi-Fi와 Bluetooth를 켭니다. 기존 페어링을 삭제하기 전에 필요한 연락처와 설정을 확인하세요.
2. 차량의 전화 또는 기기 연결 메뉴에서 iPhone을 선택해 페어링합니다. iPhone과 차량에 표시되는 확인 번호가 일치하는지 확인합니다.
3. iPhone에 CarPlay 사용 또는 허용 요청이 표시되면 내용을 확인하고 승인합니다. 기기·iOS 버전에 따라 표현과 순서는 다를 수 있습니다.
4. 차량의 CarPlay 화면을 열고 지도, 음악, 터치를 각각 확인합니다. USB 메모리와 유선 연결은 무선 연결에 영향을 줄 수 있으므로 설치가 끝난 USB를 제거하세요.

**Android 휴대폰**

1. 주차한 상태에서 휴대폰의 Wi-Fi와 Bluetooth를 켜고, 휴대폰에서 Android Auto를 사용할 수 있는지 확인합니다.
2. 차량의 전화 또는 기기 연결 메뉴에서 휴대폰을 페어링하고 양쪽 화면의 확인 번호를 확인합니다.
3. 휴대폰과 차량에 표시되는 Android Auto 연결 및 권한 요청을 읽고 필요한 경우 승인합니다. 승인 화면과 메뉴 이름은 기기·버전에 따라 다를 수 있습니다.
4. 차량의 Android Auto 화면을 열고 지도, 음악, 터치를 확인합니다. 이 순서와 화면 문구는 일반 안내이며 모든 폰에서 검증된 정확한 화면 흐름은 아닙니다.

모드가 설치 목록에 나타나는 것과 **실제 무선 연결·영상·오디오가 정상 동작하는 것**은 서로 다른 확인입니다. 한 항목이 실패하면 재시도 전에 아래 문제 해결을 확인하세요.

### 6. 문제가 발생한 경우

검은 화면이 나타나면 정차 상태에서 연결 상태와 화면을 확인하세요. 시험 차량에서는 Bluetooth 재연결과 화면 재진입 후 복구됐지만 다른 상황에서 재현되거나 해결된다는 보장은 없습니다. HMG-API에서 모드를 선택해 스위치를 끄거나 **Remove**를 선택하고 확인한 뒤 **Apply changes**, 안내에 따른 재시작을 진행하세요. 삭제·복구는 차량에서 독립적으로 검증되지 않았습니다. 부팅 반복이나 앱을 열 수 없는 경우 더 진행하지 말고 해당 설치 버전의 [공식 문제 해결](https://github.com/VysseCodes/HMG-API/wiki/Troubleshooting) 및 [제거 안내](https://github.com/VysseCodes/HMG-API/wiki/Uninstalling)를 확인하세요. 무작위 초기화·명령 실행·강제 덮어쓰기는 하지 마세요.

로그를 공유하기 전 VIN, 시리얼, 전화번호, MAC, SSID, 위치, 키와 비밀번호를 지우세요. 자세한 분기와 한계는 [문제 해결](TROUBLESHOOTING.md)을 참조하세요.
