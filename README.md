# Apktool Ultra

Android Reverse Engineering Suite - APK 디컴파일, 코드 편집, 네이티브 라이브러리 분석, 서명까지 올인원 안드로이드 앱.

## Features

### 1. APK Decompile/Recompile
- APK -> Smali 디컴파일 (DEX 파싱, baksmali 지원)
- Smali 코드 편집 (구문 강조, 줄 번호, 실행취소/다시실행)
- Smali -> DEX -> APK 리컴파일

### 2. Archive Viewer
- APK 내부 파일 구조 탐색
- 파일 추출, 교체, 추가, 삭제
- Hex 뷰어
- 텍스트 파일 직접 편집

### 3. Ghidra Android Edition (Disassembler)
- ELF (.so) 파일 파싱 (32-bit/64-bit)
- ARM/ARM64/Thumb 디스어셈블리
- 섹션, 심볼, 프로그램 헤더 분석
- 함수별 디스어셈블리
- 문자열 추출
- Hex 덤프
- 바이트 패치

### 4. Code Editor
- Smali/Java/XML 구문 강조
- 줄 번호 표시
- 실행취소/다시실행
- 검색 기능
- 자동 저장 확인

### 5. APK Signer
- V1 서명 (JAR Signing)
- 기본 키스토어 자동 생성
- 커스텀 키스토어 지원
- 키스토어 생성 도구

### 6. Tool Manager
- AAPT2 다운로드
- Baksmali/Smali 다운로드
- ApkSigner 다운로드
- Java Runtime 다운로드
- Zipalign 다운로드

## Architecture

```
com.apktool.ultra/
├── core/
│   ├── ApkDecompiler.kt    - APK 디컴파일 엔진
│   ├── ApkCompiler.kt      - APK 리컴파일 엔진
│   ├── ApkSigner.kt        - APK 서명 엔진
│   ├── ArchiveManager.kt   - ZIP 아카이브 관리
│   ├── ElfParser.kt        - ELF 파일 파서
│   ├── ArmDisassembler.kt  - ARM/ARM64 디스어셈블러
│   └── ToolDownloader.kt   - 도구 다운로드 관리자
├── ui/
│   ├── home/               - 메인 화면
│   ├── decompile/          - 디컴파일 화면
│   ├── editor/             - 코드 에디터
│   ├── archive/            - 아카이브 뷰어
│   ├── disassembler/       - 디스어셈블러 (Ghidra)
│   ├── signer/             - 서명 도구
│   └── tools/              - 도구 관리자
└── utils/
    ├── SyntaxHighlighter.kt - 구문 강조
    └── FileUtils.kt         - 파일 유틸리티
```

## Build

```bash
./gradlew assembleDebug
```

## Requirements

- Android 8.0+ (API 26)
- Storage permission
