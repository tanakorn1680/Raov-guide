# MOBA Analyzer

แอพ Android วิเคราะห์เกม MOBA (ROV) แบบ real-time ด้วย OCR + AI

## Phase ปัจจุบัน: Phase 1 — Screen Reader

อ่านข้อมูลจากหน้าจออัตโนมัติ ไม่ต้องกรอกเอง:
- ⏱ เวลาเกม (timer)
- ⚔️ สกอร์รวม (kill score)
- 🦸 ชื่อฮีโร่ที่ตรวจพบ
- 💚 HP bars
- 📊 KDA ของตัวเอง
- 🗺️ Game phase (Early / Mid / Late)

## โครงสร้าง

```
app/src/main/java/com/mobaanalyzer/
├── MainActivity.kt              ← Permission flow + UI
├── model/
│   └── GameState.kt            ← Data model ทุก field ของเกม
├── ocr/
│   ├── GameScreenReader.kt     ← ML Kit OCR engine
│   └── GameStateParser.kt      ← แปลง raw text → GameState
├── service/
│   ├── ScreenCaptureService.kt ← MediaProjection จับหน้าจอทุก 2s
│   ├── OverlayService.kt       ← Floating window ทับเกม
│   └── KeepAliveService.kt     ← WakeLock + AlarmManager guard
├── data/
│   └── AppState.kt             ← Shared state ระหว่าง services
└── receiver/
    ├── BootReceiver.kt         ← Start หลัง reboot
    └── AlarmReceiver.kt        ← Backup restart ทุก 15 นาที
```

## Permission ที่ต้องอนุญาต

1. **Overlay Permission** (Display over other apps) — แสดงผลทับเกม
2. **Notification Permission** (Android 13+)
3. **Screen Capture** (MediaProjection) — จับภาพหน้าจอ

## วิธี Build

### GitHub Actions (แนะนำ)
Push ไป `main` → GitHub จะ build APK ให้อัตโนมัติ  
ดาวน์โหลด APK ได้จาก Actions tab → Artifacts

### Local
```bash
./gradlew assembleDebug
# APK อยู่ที่: app/build/outputs/apk/debug/app-debug.apk
```

## Roadmap

- [x] **Phase 1** — Screen Reader (OCR อ่านหน้าจออัตโนมัติ)
- [ ] **Phase 2** — Game State Parser (parse HP, minimap position)
- [ ] **Phase 3** — AI Analysis Engine (hero counter-pick DB + strategy)
- [ ] **Phase 4** — Overlay UI (real-time tips + alerts)
