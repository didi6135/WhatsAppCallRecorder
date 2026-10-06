"""Capture the synthetic browser mirror; never connect to a phone or account."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

from playwright.sync_api import sync_playwright


ROOT = Path(__file__).resolve().parents[2]
PREVIEW = Path(__file__).resolve().parent
OUTPUT = ROOT / "artifacts" / "ui-preview"
URL = (PREVIEW / "index.html").as_uri()
SOURCES = [
    "src/theme/index.ts", "src/components/Visuals.tsx", "src/components/RecordButton.tsx",
    "src/screens/HomeScreen.tsx", "src/screens/RecordingsScreen.tsx",
    "src/screens/SettingsScreen.tsx", "src/screens/SetupScreen.tsx",
    "src/setup/SetupContext.tsx", "src/components/SystemSetupWizard.tsx",
    "src/setup/readiness.ts", "src/navigation/AppNavigator.tsx",
]


def inspect_layout(page):
    return page.evaluate("""() => {
      const phone=document.querySelector('.phone');
      const content=phone.querySelector('.screen-content');
      const overflow=[...phone.querySelectorAll('*')].filter(el=> {
        if (!el.getClientRects().length || getComputedStyle(el).overflowX==='hidden') return false;
        return el.scrollWidth > el.clientWidth + 2 && !['SPAN','I','B'].includes(el.tagName);
      }).map(el=>({tag:el.tagName,class:el.className,text:el.textContent.slice(0,90),width:el.clientWidth,scroll:el.scrollWidth}));
      return {documentWidth:document.documentElement.clientWidth,documentScrollWidth:document.documentElement.scrollWidth,
        phoneWidth:phone.clientWidth,contentHeight:content.clientHeight,contentScrollHeight:content.scrollHeight,
        horizontalOverflow:overflow,buttons:[...phone.querySelectorAll('button')].filter(el=>el.getClientRects().length)
          .map(el=>({text:el.innerText,width:Math.round(el.getBoundingClientRect().width),height:Math.round(el.getBoundingClientRect().height)}))};
    }""")


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    manifest = {
        "type": "synthetic static React Native visual mirror",
        "limitations": "Browser rendering only; no Android navigation, native permissions, audio, helper or device verification.",
        "sources": {name: hashlib.sha256((ROOT/name).read_bytes()).hexdigest() for name in SOURCES},
        "captures": [],
    }
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 1340, "height": 1100}, device_scale_factor=1)
        errors = []
        page.on("pageerror", lambda error: errors.append(str(error)))
        for gallery, filename in [("main", "three-main-screens.png"), ("setup", "onboarding.png")]:
            page.goto(f"{URL}?gallery={gallery}")
            page.screenshot(path=str(OUTPUT/filename), full_page=True)
            manifest["captures"].append({"file": filename, "gallery": gallery})

        for width in [320, 390, 430]:
            page.set_viewport_size({"width": width, "height": 932})
            for large in [False, True]:
                for screen in ["Home", "Recordings", "Settings", "Setup"]:
                    for step in range(6) if screen == "Setup" else [0]:
                        name = f"{screen.lower()}-{width}-{'large' if large else 'normal'}-step{step}.png"
                        page.goto(f"{URL}?screen={screen}&large={int(large)}&step={step}")
                        layout = inspect_layout(page)
                        page.locator(".phone").screenshot(path=str(OUTPUT/name))
                        manifest["captures"].append({"file": name, "screen": screen, "width": width, "largeText": large, "step": step, **layout})
        page.set_viewport_size({"width": 430, "height": 932})
        for screen, mode in [("Home", "pending"), ("Home", "recording"), ("Home", "error"),
                             ("Recordings", "empty"), ("Recordings", "error"),
                             ("Settings", "pending"), ("Settings", "error")]:
            name = f"{screen.lower()}-{mode}.png"
            page.goto(f"{URL}?screen={screen}&mode={mode}")
            layout = inspect_layout(page)
            page.locator(".phone").screenshot(path=str(OUTPUT/name))
            manifest["captures"].append({"file": name, "screen": screen, "mode": mode, **layout})

        page.goto(URL)
        page.get_by_role("button", name="ההקלטות שלי", exact=True).first.click()
        assert page.locator('.phone[data-view="Recordings"]').count() == 1
        page.get_by_role("button", name="הגדרות", exact=True).first.click()
        assert page.locator('.phone[data-view="Settings"]').count() == 1
        manifest["interactionChecks"] = ["screen switch to recordings", "screen switch to settings"]
        manifest["pageErrors"] = errors
        browser.close()
    (OUTPUT/"manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    failures = [capture for capture in manifest["captures"] if capture.get("horizontalOverflow")]
    print(json.dumps({"output": str(OUTPUT), "captures": len(manifest["captures"]),
                      "pageErrors": errors, "horizontalOverflowCases": len(failures)}, ensure_ascii=False))
    if errors or failures:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
