# 배포 상태와 출처 / Distribution and attribution

## 한국어

공개 [Pre-release](https://github.com/lkhwan0204/Palisade-LX2-Wireless-Projection/releases/tag/v2.1.4-lx2dev.12-noaa-tbt.1)는 **실차 미검증 실험판** `wireless-carplay-aa-lx2dev-2.1.4-lx2dev.12-noaa-tbt.1.zip`과 [SHA256SUMS](https://github.com/lkhwan0204/Palisade-LX2-Wireless-Projection/releases/download/v2.1.4-lx2dev.12-noaa-tbt.1/SHA256SUMS)를 제공합니다. SHA-256: `ff99564ae90cb650beda6581b270893ff3d370d48cd2131619f24f9371b36749`. 이 후보는 Android Auto 계기판·HUD 길안내 레시피 두 개를 제외했으며, 사용자가 실차 성공을 보고한 원본 `2.1.3-lx2dev.12`와 다른 빌드입니다. 기본 연결과 제거·복구는 실차 미검증이며 **설치를 권장하지 않습니다**. 기존 모드 위에 설치하면 버전이 올라 이전 ZIP으로 다운그레이드가 거부될 수 있습니다.

원본 lx2dev.12 ZIP은 전체 대응 소스·빌드 일치 및 원본 Android Auto 기계어 레시피의 출처·재배포 조건 확인을 위해 계속 보류됩니다. 실차 성공 당시 정확한 HMG-API 코어 및 LX2 맵 조합도 미확인입니다. 공개 실험판의 [대응 소스·빌드 안내·라이선스 고지](../source/lx2dev-noaa-tbt/BUILDING.md)가 제공됩니다. 소스에서 재빌드한 후보 `hooks.jar`와 매니페스트는 일치했지만, 이것이 실차 동작이나 원본 .12 바이너리와의 동등성을 보장하지는 않습니다.

HMG-API는 AGPL-3.0, NEMESIS는 AGPL-3.0이며 HMG Leafia Plus는 자체 레시피 및 CarPlay 패치를 GPL-3.0으로 고지합니다. 해당 원본 고지와 라이선스는 소스에 보존했습니다. 면책 문구 자체가 제3자 재배포 권리를 부여하지는 않습니다.

## English

The published [pre-release](https://github.com/lkhwan0204/Palisade-LX2-Wireless-Projection/releases/tag/v2.1.4-lx2dev.12-noaa-tbt.1) provides the **vehicle-untested experimental** `wireless-carplay-aa-lx2dev-2.1.4-lx2dev.12-noaa-tbt.1.zip` and [SHA256SUMS](https://github.com/lkhwan0204/Palisade-LX2-Wireless-Projection/releases/download/v2.1.4-lx2dev.12-noaa-tbt.1/SHA256SUMS). SHA-256: `ff99564ae90cb650beda6581b270893ff3d370d48cd2131619f24f9371b36749`. It excludes two Android Auto cluster/HUD guidance recipes and is a different build from the original `2.1.3-lx2dev.12` for which the owner reported a successful vehicle test. Basic connections, removal and recovery are untested on a car; **installation is not recommended**. Installing it over the existing mod raises the version and may prevent an ordinary downgrade to an older ZIP.

The original lx2dev.12 ZIP remains withheld pending complete source/build correspondence and provenance/redistribution review of its Android Auto machine-code recipes. The exact HMG-API core and LX2 map combination from the reported test is also unconfirmed. [Corresponding experimental source, build instructions and notices](../source/lx2dev-noaa-tbt/BUILDING.md) are provided. A rebuilt candidate `hooks.jar` and manifest matched, but this does not prove vehicle operation or equivalence to the original .12 binary.

HMG-API and NEMESIS are AGPL-3.0; HMG Leafia Plus identifies its original recipes and CarPlay patches as GPL-3.0. Relevant upstream notices and license texts are retained in the source. A disclaimer does not itself grant third-party redistribution rights.
