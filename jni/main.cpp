// ProMenu - touch-friendly cheat menu overlay for GTA SA Android (AML plugin, arm64).
// Stage 1: floating circle button + panel + a handful of CCheat actions.
//
// Hook points (all four were verified in the original ARM CheatMenu binary):
//   AND_TouchEvent                    -> touch input (1 = down, 2 = move, 3 = up)
//   CDebug::DebugDisplayTextBuffer    -> once per frame, after the HUD: draw ImGui
//   CGame::ShutdownRenderWare         -> drop the font texture before GL goes away
//   CClock::Update                    -> game tick: run queued cheats
#include <mod/amlmod.h>
#include <mod/logger.h>
#include <stdint.h>
#include <stdio.h>
#include <time.h>
#include "imgui.h"

MYMOD(net.promenu.cheatmenu, ProMenu, 0.1, ProMenu)
NEEDGAME(com.rockstargames.gtasa)

// ---------------------------------------------------------------- symbols
static void* g_hGame = nullptr;

static uintptr_t GameSym(const char* name) { return aml->GetSym(g_hGame, name); }

template <typename T>
static bool ResolveSym(T& out, const char* name) {
    uintptr_t a = GameSym(name);
    out = reinterpret_cast<T>(a);
    return a != 0;
}

#include "rw_backend.h"
#include "ui.h"

// ------------------------------------------------------------------ state
static bool   g_inited = false, g_failed = false;
static int*   g_pGameState = nullptr;     // gGameState   (7 = front-end menu, 9 = playing)
static char*  g_pMobileMenu = nullptr;    // gMobileMenu  (int at +0x24 != 0 -> pause menu is open)
static char*  g_pRsGlobal = nullptr;      // RsGlobal     (int width at +8, int height at +12)
static double g_lastT = 0.0;

static double NowSec() {
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec + (double)ts.tv_nsec * 1e-9;
}

static bool FileExists(const char* path) {
    FILE* f = fopen(path, "rb");
    if (!f) return false;
    fclose(f);
    return true;
}

static bool ScreenSize(float& w, float& h) {
    if (!g_pRsGlobal) return false;
    const int* p = reinterpret_cast<const int*>(g_pRsGlobal + 8);
    w = (float)p[0];
    h = (float)p[1];
    return w > 0.f && h > 0.f;
}

static bool InitImGui() {
    if (!RWB::Resolve()) return false;

    g_pGameState  = reinterpret_cast<int*>(GameSym("gGameState"));
    g_pMobileMenu = reinterpret_cast<char*>(GameSym("gMobileMenu"));
    g_pRsGlobal   = reinterpret_cast<char*>(GameSym("RsGlobal"));
    if (!g_pGameState || !g_pRsGlobal) { logger->Error("gGameState / RsGlobal not found"); return false; }

    float w = 0, h = 0;
    if (!ScreenSize(w, h)) return false;       // RenderWare not ready yet, try next frame
    const float sc = (w < h ? w : h) / 1080.f;

    IMGUI_CHECKVERSION();
    ImGui::CreateContext();
    ImGuiIO& io = ImGui::GetIO();
    io.IniFilename = nullptr;
    io.LogFilename = nullptr;
    io.ConfigFlags |= ImGuiConfigFlags_NoMouseCursorChange;
    io.MouseDrawCursor = false;

    // font: user font first, then the Arial that ships with the old menu, then ImGui's built-in
    static const char* kFonts[] = {
        "/sdcard/Android/data/com.rockstargames.gtasa/files/ProMenu/font.ttf",
        "/storage/emulated/0/Android/data/com.rockstargames.gtasa/files/ProMenu/font.ttf",
        "/sdcard/Android/data/com.rockstargames.gtasa/files/ARM/CheatMenu/Font/MyFont.ttf",
        "/storage/emulated/0/Android/data/com.rockstargames.gtasa/files/ARM/CheatMenu/Font/MyFont.ttf",
    };
    ImFont* font = nullptr;
    for (const char* path : kFonts) {
        if (!FileExists(path)) continue;
        font = io.Fonts->AddFontFromFileTTF(path, 30.f * sc);
        if (font) { logger->Info("font: %s", path); break; }
    }
    if (!font) {
        ImFontConfig cfg;
        cfg.SizePixels = 30.f * sc;
        io.Fonts->AddFontDefault(&cfg);
        logger->Info("font: built-in");
    }

    UI::Init(sc);
    UI::g_dbgCheatsFound = UI::ResolveCheats(GameSym);
    logger->Info("ImGui ready, screen %dx%d, cheats %d/%d", (int)w, (int)h, UI::g_dbgCheatsFound, UI::kCheatCount);
    g_lastT = NowSec();
    return true;
}

static void Frame() {
    if (g_failed) return;
    if (!g_inited) {
        if (!g_hGame) return;
        if (!InitImGui()) { if (!g_pGameState) g_failed = true; return; }
        g_inited = true;
    }

    const int state = *g_pGameState;
    const bool pauseMenu = g_pMobileMenu && *reinterpret_cast<const int*>(g_pMobileMenu + 0x24) != 0;
    const bool show = (state != 7) && !pauseMenu;
    UI::g_dbgGameState = state;
    UI::g_canRun = (state == 9) && !pauseMenu;
    UI::SetVisible(show);
    if (!show) return;

    float w = 0, h = 0;
    if (!ScreenSize(w, h)) return;

    const double now = NowSec();
    float dt = (float)(now - g_lastT);
    g_lastT = now;
    if (dt < 1.f / 240.f) dt = 1.f / 240.f;
    if (dt > 0.1f) dt = 0.1f;

    if (!RWB::NewFrame(w, h, dt)) return;
    ImGui::NewFrame();
    UI::Draw(w, h, dt);
    ImGui::Render();
    RWB::RenderDrawData(ImGui::GetDrawData());
}

// ------------------------------------------------------------------ hooks
DECL_HOOKv(DebugDisplayTextBuffer)
{
    DebugDisplayTextBuffer();       // original first, then we draw on top
    Frame();
}

DECL_HOOKv(AND_TouchEvent, int type, int idx, int x, int y)
{
    if (g_inited && UI::OnTouch(type, idx, x, y)) return;   // menu consumed it
    AND_TouchEvent(type, idx, x, y);
}

DECL_HOOKv(ShutdownRenderWare)
{
    if (g_inited) RWB::DestroyFontRaster();     // recreated automatically on the next frame
    ShutdownRenderWare();
}

DECL_HOOKv(ClockUpdate)
{
    ClockUpdate();
    if (g_inited) UI::RunQueued(UI::g_canRun);
}

// --------------------------------------------------------------- AML entry
ON_MOD_PRELOAD()
{
    logger->SetTag("ProMenu");
}

ON_MOD_LOAD()
{
    logger->SetTag("ProMenu");
    g_hGame = aml->GetLibHandle("libGTASA.so");
    if (!g_hGame) { logger->Error("libGTASA.so not found, plugin disabled"); return; }

    // only hook when every target exists, so a wrong game version can never crash the game
    const char* kTouch    = "_Z14AND_TouchEventiiii";
    const char* kFrame    = "_ZN6CDebug22DebugDisplayTextBufferEv";
    const char* kShutdown = "_ZN5CGame18ShutdownRenderWareEv";
    const char* kClock    = "_ZN6CClock6UpdateEv";
    if (!GameSym(kTouch) || !GameSym(kFrame) || !GameSym(kShutdown) || !GameSym(kClock)) {
        logger->Error("game symbols missing (wrong game version?), plugin disabled");
        return;
    }

    HOOKSYM(AND_TouchEvent,           g_hGame, kTouch);
    HOOKSYM(DebugDisplayTextBuffer,   g_hGame, kFrame);
    HOOKSYM(ShutdownRenderWare,       g_hGame, kShutdown);
    HOOKSYM(ClockUpdate,              g_hGame, kClock);
    logger->Info("loaded, hooks installed");
}
