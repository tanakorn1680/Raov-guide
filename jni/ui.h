#pragma once
// Floating circle button + slide-in cheat panel, built for touch.
#include <float.h>
#include <math.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include "imgui.h"
#include "imgui_internal.h"   // ClearActiveID()

namespace UI {

// ---------------------------------------------------------------- cheats
// These are the game's own CCheat functions (the same ones the typed cheat
// codes call).  Symbol names were taken from the original menu's import list.
struct Cheat { int tab; const char* label; const char* sym; int arg; void* fn; };

static Cheat g_cheats[] = {
    // 0 = Player
    {0, "Health, Armor & $250k", "_ZN6CCheat22MoneyArmourHealthCheatEv", -1, nullptr},
    {0, "Jetpack",               "_ZN6CCheat12JetpackCheatEv",           -1, nullptr},
    {0, "Parachute",             "_ZN6CCheat14ParachuteCheatEv",         -1, nullptr},
    {0, "Adrenaline",            "_ZN6CCheat15AdrenalineCheatEv",        -1, nullptr},
    {0, "Max weapon skills",     "_ZN6CCheat17WeaponSkillsCheatEv",      -1, nullptr},
    {0, "Max driving skills",    "_ZN6CCheat18VehicleSkillsCheatEv",     -1, nullptr},
    {0, "Clear wanted level",    "_ZN6CCheat14NotWantedCheatEv",         -1, nullptr},
    {0, "Wanted level +2",       "_ZN6CCheat18WantedLevelUpCheatEv",     -1, nullptr},
    {0, "Wanted level -2",       "_ZN6CCheat20WantedLevelDownCheatEv",   -1, nullptr},
    // 1 = Vehicles (arg = model id)
    {1, "Infernus",   "_ZN6CCheat12VehicleCheatEi", 411, nullptr},
    {1, "Cheetah",    "_ZN6CCheat12VehicleCheatEi", 415, nullptr},
    {1, "Banshee",    "_ZN6CCheat12VehicleCheatEi", 429, nullptr},
    {1, "Turismo",    "_ZN6CCheat12VehicleCheatEi", 451, nullptr},
    {1, "Bullet",     "_ZN6CCheat12VehicleCheatEi", 541, nullptr},
    {1, "NRG-500",    "_ZN6CCheat12VehicleCheatEi", 522, nullptr},
    {1, "Sanchez",    "_ZN6CCheat12VehicleCheatEi", 468, nullptr},
    {1, "Quad",       "_ZN6CCheat12VehicleCheatEi", 471, nullptr},
    {1, "Monster",    "_ZN6CCheat12VehicleCheatEi", 444, nullptr},
    {1, "Rhino",      "_ZN6CCheat12VehicleCheatEi", 432, nullptr},
    {1, "Hunter",     "_ZN6CCheat12VehicleCheatEi", 425, nullptr},
    {1, "Hydra",      "_ZN6CCheat12VehicleCheatEi", 520, nullptr},
    {1, "Sparrow",    "_ZN6CCheat12VehicleCheatEi", 469, nullptr},
    {1, "Stunt plane","_ZN6CCheat12VehicleCheatEi", 513, nullptr},
    {1, "Jetmax",     "_ZN6CCheat12VehicleCheatEi", 493, nullptr},
    // 2 = Weapons
    {2, "Weapon set 1", "_ZN6CCheat12WeaponCheat1Ev", -1, nullptr},
    {2, "Weapon set 2", "_ZN6CCheat12WeaponCheat2Ev", -1, nullptr},
    {2, "Weapon set 3", "_ZN6CCheat12WeaponCheat3Ev", -1, nullptr},
    // 3 = World
    {3, "Sunny weather",  "_ZN6CCheat17SunnyWeatherCheatEv",      -1, nullptr},
    {3, "Extra sunny",    "_ZN6CCheat22ExtraSunnyWeatherCheatEv", -1, nullptr},
    {3, "Rainy weather",  "_ZN6CCheat17RainyWeatherCheatEv",      -1, nullptr},
    {3, "Midnight",       "_ZN6CCheat13MidnightCheatEv",          -1, nullptr},
    {3, "Fast time",      "_ZN6CCheat13FastTimeCheatEv",          -1, nullptr},
    {3, "Slow time",      "_ZN6CCheat13SlowTimeCheatEv",          -1, nullptr},
};
static const int kCheatCount = (int)(sizeof(g_cheats) / sizeof(g_cheats[0]));
static const char* kTabs[] = {"Player", "Vehicles", "Weapons", "World", "Settings"};
static const int kTabCount = 5;
static const int kSettingsTab = 4;

static int ResolveCheats(uintptr_t (*lookup)(const char*)) {
    int found = 0;
    for (int i = 0; i < kCheatCount; i++) {
        uintptr_t a = lookup(g_cheats[i].sym);
        g_cheats[i].fn = (void*)a;
        if (a) found++;
    }
    return found;
}

// cheats are queued from the UI and executed inside the game's update tick
static const int kQueueSize = 16;
static int g_queue[kQueueSize];
static volatile int g_qHead = 0, g_qTail = 0;

static void Enqueue(int cheatIndex) {
    int next = (g_qTail + 1) % kQueueSize;
    if (next == g_qHead) return;
    g_queue[g_qTail] = cheatIndex;
    g_qTail = next;
}

static void RunQueued(bool gameIsRunning) {
    while (g_qHead != g_qTail) {
        int i = g_queue[g_qHead];
        g_qHead = (g_qHead + 1) % kQueueSize;
        if (!gameIsRunning) continue;
        const Cheat& c = g_cheats[i];
        if (!c.fn) continue;
        if (c.arg < 0) ((void (*)())c.fn)();
        else           ((void (*)(int))c.fn)(c.arg);
    }
}

// ---------------------------------------------------------------- state
static float g_sc = 1.f;                    // UI scale (screen height / 1080)
static ImVec2 g_fab = ImVec2(0, 0);         // top-left of the floating button (undocked)
static bool g_posInit = false;
static bool g_open = false, g_dragging = false;
static float g_openAnim = 0.f, g_dock = 0.f, g_idle = 0.f;
static float g_btnAlpha = 0.92f, g_btnScale = 1.f;
static int g_tab = 0;
static char g_toast[64] = "";
static float g_toastT = 0.f;

// shown in Settings, filled in by main.cpp
static int g_dbgGameState = -1, g_dbgCheatsFound = 0;
static bool g_canRun = false;               // game is in "playing" state

// ------------------------------------------------------------- touch input
// Touches arrive on the game's input callback; ImGui must only be fed from the
// render thread, so events go through a small locked ring buffer.
struct TEv { int kind; float x, y; };       // 0 = move, 1 = down, 2 = up, 3 = pointer left
static TEv g_ev[96];
static int g_evHead = 0, g_evTail = 0;
static pthread_mutex_t g_evLock = PTHREAD_MUTEX_INITIALIZER;

static void PushEv(int kind, float x, float y) {
    pthread_mutex_lock(&g_evLock);
    int next = (g_evTail + 1) % 96;
    if (next != g_evHead) { g_ev[g_evTail].kind = kind; g_ev[g_evTail].x = x; g_ev[g_evTail].y = y; g_evTail = next; }
    pthread_mutex_unlock(&g_evLock);
}

static void FlushEvents(ImGuiIO& io) {
    pthread_mutex_lock(&g_evLock);
    while (g_evHead != g_evTail) {
        TEv e = g_ev[g_evHead];
        g_evHead = (g_evHead + 1) % 96;
        switch (e.kind) {
            case 0: io.AddMousePosEvent(e.x, e.y); break;
            case 1: io.AddMousePosEvent(e.x, e.y); io.AddMouseButtonEvent(0, true); break;
            case 2: io.AddMousePosEvent(e.x, e.y); io.AddMouseButtonEvent(0, false); break;
            default: io.AddMousePosEvent(-FLT_MAX, -FLT_MAX); break;
        }
    }
    pthread_mutex_unlock(&g_evLock);
}

// areas that swallow touches (updated every frame while visible)
static volatile bool g_visible = false, g_panelValid = false;
static volatile float g_fabCx = 0, g_fabCy = 0, g_fabR = 0;
static volatile float g_pX0 = 0, g_pY0 = 0, g_pX1 = 0, g_pY1 = 0;
static int g_uiFinger = -1;

static bool HitTest(float x, float y) {
    if (!g_visible) return false;
    float dx = x - g_fabCx, dy = y - g_fabCy;
    if (dx * dx + dy * dy <= g_fabR * g_fabR) return true;
    return g_panelValid && x >= g_pX0 && x <= g_pX1 && y >= g_pY0 && y <= g_pY1;
}

// returns true when the touch belongs to the menu and must NOT reach the game
// type: 1 = down, 2 = move, 3 = up  (same codes the original menu uses)
static bool OnTouch(int type, int idx, int x, int y) {
    const float fx = (float)x, fy = (float)y;
    switch (type) {
        case 1:
            if (g_uiFinger < 0 && HitTest(fx, fy)) { g_uiFinger = idx; PushEv(1, fx, fy); return true; }
            return false;
        case 2:
            if (idx == g_uiFinger) { PushEv(0, fx, fy); return true; }
            return false;
        case 3:
            if (idx == g_uiFinger) { PushEv(2, fx, fy); PushEv(3, 0, 0); g_uiFinger = -1; return true; }
            return false;
    }
    return false;
}

// ---------------------------------------------------------------- helpers
static void Toast(const char* text) {
    snprintf(g_toast, sizeof(g_toast), "%s", text);
    g_toastT = 1.6f;
}

static void OnCheatPressed(int i) {
    if (!g_canRun) { Toast("Start playing first"); return; }
    Enqueue(i);
    char msg[64];
    snprintf(msg, sizeof(msg), "%s", g_cheats[i].label);
    Toast(msg);
}

static float Clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

// Drag-to-scroll for the current window (ImGui only scrolls with a wheel / bar).
// Cancels the button press once the finger starts moving.
static void TouchScroll() {
    ImGuiIO& io = ImGui::GetIO();
    if (!io.MouseDown[0]) return;
    ImVec2 p = ImGui::GetWindowPos(), s = ImGui::GetWindowSize(), c = io.MouseClickedPos[0];
    if (c.x < p.x || c.y < p.y || c.x > p.x + s.x || c.y > p.y + s.y) return;
    if (!ImGui::IsMouseDragging(0, 14.f * g_sc)) return;
    ImGui::SetScrollY(ImGui::GetScrollY() - io.MouseDelta.y);
    ImGui::ClearActiveID();
}

static void Init(float scale) {
    g_sc = scale;
    ImGuiStyle& st = ImGui::GetStyle();
    ImGui::StyleColorsDark();
    st.WindowRounding = 28.f * scale;
    st.ChildRounding = 20.f * scale;
    st.FrameRounding = 18.f * scale;
    st.ScrollbarSize = 12.f * scale;
    st.ScrollbarRounding = 6.f * scale;
    st.ItemSpacing = ImVec2(14.f * scale, 14.f * scale);
    st.FramePadding = ImVec2(18.f * scale, 14.f * scale);
    st.WindowBorderSize = 0.f;
    st.ChildBorderSize = 0.f;
    st.Colors[ImGuiCol_ChildBg]        = ImVec4(0, 0, 0, 0);
    st.Colors[ImGuiCol_Button]         = ImVec4(0.16f, 0.19f, 0.27f, 1.f);
    st.Colors[ImGuiCol_ButtonHovered]  = ImVec4(0.16f, 0.19f, 0.27f, 1.f);   // no sticky hover on touch
    st.Colors[ImGuiCol_ButtonActive]   = ImVec4(0.20f, 0.47f, 0.96f, 1.f);
    st.Colors[ImGuiCol_ScrollbarBg]    = ImVec4(0, 0, 0, 0);
    st.Colors[ImGuiCol_ScrollbarGrab]  = ImVec4(1, 1, 1, 0.25f);
}

// called by main.cpp every frame before Draw()
static void SetVisible(bool visible) {
    if (g_visible && !visible && g_uiFinger >= 0) {   // menu vanished under a finger
        PushEv(2, 0, 0); PushEv(3, 0, 0); g_uiFinger = -1;
    }
    g_visible = visible;
    if (!visible) g_panelValid = false;
}

// ------------------------------------------------------------------- draw
static void Draw(float W, float H, float dt) {
    ImGuiIO& io = ImGui::GetIO();
    FlushEvents(io);

    const float sc = g_sc;
    const float D = 96.f * sc * g_btnScale;
    const float margin = 20.f * sc;
    if (!g_posInit) { g_fab = ImVec2(W - D - margin, H * 0.30f); g_posInit = true; }

    const bool onRight = (g_fab.x + D * 0.5f) > W * 0.5f;

    // ---- floating button -------------------------------------------------
    const float dockTarget = (!g_open && !g_dragging && g_idle > 3.f) ? 1.f : 0.f;
    g_dock += (dockTarget - g_dock) * fminf(1.f, dt * 6.f);
    const float shift = g_dock * D * 0.55f * (onRight ? 1.f : -1.f);
    const ImVec2 pos(g_fab.x + shift, g_fab.y);
    const float alpha = g_btnAlpha * (1.f - 0.55f * g_dock);

    const ImGuiWindowFlags fabFlags = ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_NoBackground |
        ImGuiWindowFlags_NoMove | ImGuiWindowFlags_NoSavedSettings | ImGuiWindowFlags_NoNav |
        ImGuiWindowFlags_NoFocusOnAppearing | ImGuiWindowFlags_NoBringToFrontOnFocus;

    ImGui::SetNextWindowPos(pos);
    ImGui::SetNextWindowSize(ImVec2(D, D));
    ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding, ImVec2(0, 0));
    ImGui::Begin("##fab", nullptr, fabFlags);
    ImGui::InvisibleButton("##fabbtn", ImVec2(D, D));
    const bool active = ImGui::IsItemActive();
    if (active) {
        g_idle = 0.f;
        if (!g_dragging && ImGui::IsMouseDragging(0, 10.f * sc)) g_dragging = true;
        if (g_dragging) { g_fab.x += io.MouseDelta.x; g_fab.y += io.MouseDelta.y; }
    } else {
        g_idle += dt;
    }
    if (ImGui::IsItemDeactivated()) {
        if (!g_dragging) { g_open = !g_open; Toast(""); }
        g_dragging = false;
    }
    if (g_open) g_idle = 0.f;

    // snap to the nearest screen edge when released
    if (!active) {
        const float tx = onRight ? (W - D - margin) : margin;
        g_fab.x += (tx - g_fab.x) * fminf(1.f, dt * 12.f);
    }
    g_fab.y = Clamp(g_fab.y, margin, H - D - margin);

    {
        ImDrawList* dl = ImGui::GetWindowDrawList();
        const ImVec2 c(pos.x + D * 0.5f, pos.y + D * 0.5f);
        const float r = D * 0.5f;
        #define A(base) ((int)((base) * alpha))
        dl->AddCircleFilled(ImVec2(c.x, c.y + 5.f * sc), r + sc, IM_COL32(0, 0, 0, A(90)), 48);
        ImU32 body = g_open ? IM_COL32(235, 87, 87, A(255)) : IM_COL32(52, 120, 246, A(255));
        if (active) body = g_open ? IM_COL32(200, 60, 60, A(255)) : IM_COL32(35, 95, 215, A(255));
        dl->AddCircleFilled(c, r, body, 48);
        dl->AddCircle(c, r - 2.f * sc, IM_COL32(255, 255, 255, A(70)), 48, 2.f * sc);
        const ImU32 ic = IM_COL32(255, 255, 255, A(255));
        const float s = r * 0.38f, th = 5.f * sc;
        if (g_open) {
            dl->AddLine(ImVec2(c.x - s, c.y - s), ImVec2(c.x + s, c.y + s), ic, th);
            dl->AddLine(ImVec2(c.x - s, c.y + s), ImVec2(c.x + s, c.y - s), ic, th);
        } else {
            for (int i = -1; i <= 1; i++)
                dl->AddLine(ImVec2(c.x - s, c.y + i * s * 0.8f), ImVec2(c.x + s, c.y + i * s * 0.8f), ic, th);
        }
        #undef A
        g_fabCx = c.x; g_fabCy = c.y; g_fabR = r + 14.f * sc;
    }
    ImGui::End();
    ImGui::PopStyleVar();

    // ---- panel ------------------------------------------------------------
    g_openAnim += ((g_open ? 1.f : 0.f) - g_openAnim) * fminf(1.f, dt * 14.f);
    if (g_toastT > 0.f) g_toastT -= dt;
    if (!g_open && g_openAnim < 0.02f) { g_panelValid = false; return; }

    const float pw = fminf(W * 0.64f, 1280.f * sc), ph = fminf(H * 0.90f, 920.f * sc);
    const float gap = 18.f * sc;
    float px = onRight ? (g_fab.x - gap - pw) : (g_fab.x + D + gap);
    px = Clamp(px, gap, W - pw - gap);
    px += (onRight ? 1.f : -1.f) * (1.f - g_openAnim) * 60.f * sc;     // slide in
    const float py = (H - ph) * 0.5f;
    g_pX0 = px; g_pY0 = py; g_pX1 = px + pw; g_pY1 = py + ph;
    g_panelValid = true;

    const ImGuiWindowFlags panelFlags = ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_NoMove |
        ImGuiWindowFlags_NoSavedSettings | ImGuiWindowFlags_NoNav | ImGuiWindowFlags_NoScrollWithMouse |
        ImGuiWindowFlags_NoFocusOnAppearing | ImGuiWindowFlags_NoBringToFrontOnFocus;

    ImGui::SetNextWindowPos(ImVec2(px, py));
    ImGui::SetNextWindowSize(ImVec2(pw, ph));
    ImGui::PushStyleVar(ImGuiStyleVar_Alpha, g_openAnim);
    ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding, ImVec2(16.f * sc, 12.f * sc));
    ImGui::PushStyleColor(ImGuiCol_WindowBg, ImVec4(0.07f, 0.08f, 0.11f, 0.95f));
    ImGui::Begin("##panel", nullptr, panelFlags);

    const float hh = 76.f * sc;                  // header height
    const float btnH = 84.f * sc;                // standard button height

    // header: title + big close button
    ImGui::SetCursorPos(ImVec2(14.f * sc, (hh - ImGui::GetFontSize()) * 0.5f));
    ImGui::TextColored(ImVec4(1, 1, 1, 1), "CHEAT MENU");
    ImGui::SameLine();
    ImGui::TextColored(ImVec4(0.55f, 0.60f, 0.70f, 1), "  tap the circle or X to close");
    {
        const float cs = 60.f * sc;
        ImGui::SetCursorPos(ImVec2(pw - cs - 28.f * sc, (hh - cs) * 0.5f));
        ImGui::PushStyleColor(ImGuiCol_Button,        ImVec4(0.86f, 0.28f, 0.28f, 1));
        ImGui::PushStyleColor(ImGuiCol_ButtonHovered, ImVec4(0.86f, 0.28f, 0.28f, 1));
        ImGui::PushStyleColor(ImGuiCol_ButtonActive,  ImVec4(0.65f, 0.18f, 0.18f, 1));
        if (ImGui::Button("X", ImVec2(cs, cs))) g_open = false;
        ImGui::PopStyleColor(3);
    }

    ImGui::SetCursorPos(ImVec2(16.f * sc, hh));
    const float bodyH = ImGui::GetContentRegionAvail().y;
    const float sideW = fminf(260.f * sc, pw * 0.26f);

    // sidebar
    ImGui::BeginChild("##side", ImVec2(sideW, bodyH), 0, ImGuiWindowFlags_NoScrollbar);
    for (int t = 0; t < kTabCount; t++) {
        const bool sel = (g_tab == t);
        ImGui::PushStyleColor(ImGuiCol_Button,        sel ? ImVec4(0.20f, 0.47f, 0.96f, 1) : ImVec4(1, 1, 1, 0.06f));
        ImGui::PushStyleColor(ImGuiCol_ButtonHovered, sel ? ImVec4(0.20f, 0.47f, 0.96f, 1) : ImVec4(1, 1, 1, 0.06f));
        if (ImGui::Button(kTabs[t], ImVec2(ImGui::GetContentRegionAvail().x, btnH))) g_tab = t;
        ImGui::PopStyleColor(2);
    }
    ImGui::EndChild();

    ImGui::SameLine();

    // content
    ImGui::BeginChild("##content", ImVec2(0, bodyH), 0, 0);
    if (g_tab != kSettingsTab) {
        const float availW = ImGui::GetContentRegionAvail().x - ImGui::GetStyle().ScrollbarSize;
        const float colW = (availW - ImGui::GetStyle().ItemSpacing.x) * 0.5f;
        int n = 0;
        for (int i = 0; i < kCheatCount; i++) {
            if (g_cheats[i].tab != g_tab) continue;
            if (n % 2) ImGui::SameLine();
            ImGui::PushID(i);
            const bool missing = (g_cheats[i].fn == nullptr);
            if (missing) ImGui::BeginDisabled();
            if (ImGui::Button(g_cheats[i].label, ImVec2(colW, btnH))) OnCheatPressed(i);
            if (missing) ImGui::EndDisabled();
            ImGui::PopID();
            n++;
        }
    } else {
        const float sq = 76.f * sc;
        ImGui::Text("Button opacity: %d%%", (int)(g_btnAlpha * 100.f + 0.5f));
        if (ImGui::Button("-##a", ImVec2(sq, sq))) g_btnAlpha = Clamp(g_btnAlpha - 0.10f, 0.15f, 1.f);
        ImGui::SameLine();
        if (ImGui::Button("+##a", ImVec2(sq, sq))) g_btnAlpha = Clamp(g_btnAlpha + 0.10f, 0.15f, 1.f);
        ImGui::Spacing();
        ImGui::Text("Button size: %d%%", (int)(g_btnScale * 100.f + 0.5f));
        if (ImGui::Button("-##s", ImVec2(sq, sq))) g_btnScale = Clamp(g_btnScale - 0.10f, 0.70f, 1.50f);
        ImGui::SameLine();
        if (ImGui::Button("+##s", ImVec2(sq, sq))) g_btnScale = Clamp(g_btnScale + 0.10f, 0.70f, 1.50f);
        ImGui::Spacing();
        if (ImGui::Button("Reset button position", ImVec2(ImGui::GetContentRegionAvail().x - ImGui::GetStyle().ScrollbarSize, btnH)))
            g_posInit = false;
        ImGui::Spacing();
        ImGui::TextColored(ImVec4(0.55f, 0.60f, 0.70f, 1), "ProMenu 0.1 (stage 1, untested build)");
        ImGui::TextColored(ImVec4(0.55f, 0.60f, 0.70f, 1), "Game state: %d   Cheats found: %d/%d",
                           g_dbgGameState, g_dbgCheatsFound, kCheatCount);
    }
    TouchScroll();
    ImGui::EndChild();

    // toast
    if (g_toastT > 0.f && g_toast[0]) {
        ImDrawList* dl = ImGui::GetWindowDrawList();
        const ImVec2 wp = ImGui::GetWindowPos();
        const ImVec2 ts = ImGui::CalcTextSize(g_toast);
        const float a = Clamp(g_toastT / 0.4f, 0.f, 1.f);
        const float bx = wp.x + (pw - ts.x) * 0.5f, by = wp.y + ph - 70.f * sc;
        dl->AddRectFilled(ImVec2(bx - 24.f * sc, by - 10.f * sc), ImVec2(bx + ts.x + 24.f * sc, by + ts.y + 10.f * sc),
                          IM_COL32(20, 130, 80, (int)(235 * a)), 18.f * sc);
        dl->AddText(ImVec2(bx, by), IM_COL32(255, 255, 255, (int)(255 * a)), g_toast);
    }

    ImGui::End();
    ImGui::PopStyleColor();
    ImGui::PopStyleVar(2);
}

} // namespace UI
