"""Bounded local website checks. No phone, provider, or APK download actions."""
import argparse
import json
from pathlib import Path
from playwright.sync_api import sync_playwright


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--url', default='http://127.0.0.1:8768/')
    parser.add_argument('--output', default='artifacts/website')
    args = parser.parse_args()
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    results = []
    errors = []

    def check(condition, label):
        if not condition:
            raise AssertionError(label)
        results.append(label)

    def no_overflow(page, label):
        width = page.evaluate('document.documentElement.scrollWidth')
        check(width <= page.viewport_size['width'] + 1, f'{label}: no horizontal overflow ({width}px)')

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        context = browser.new_context(viewport={'width': 390, 'height': 844}, reduced_motion='reduce')
        page = context.new_page()
        page.on('pageerror', lambda error: errors.append(str(error)))
        page.on('console', lambda message: errors.append(message.text) if message.type == 'error' else None)
        page.goto(args.url, wait_until='networkidle')
        check(page.locator('#apk-download').get_attribute('href') is None, 'pending metadata has no download URL')
        check(page.locator('#release-details').is_hidden(), 'pending metadata hides release details')
        for language in ['he', 'en']:
            page.reload(wait_until='networkidle')
            page.locator(f'[data-language="{language}"]').click()
            check(page.locator('html').get_attribute('lang') == language, f'{language}: document language')
            check(page.locator('html').get_attribute('dir') == ('rtl' if language == 'he' else 'ltr'), f'{language}: document direction')
            check(page.locator(f'[data-language="{language}"]').get_attribute('aria-pressed') == 'true', f'{language}: active language announced')
            check(page.locator('[data-i18n]').evaluate_all('(nodes) => nodes.every(n => n.textContent.trim().length > 0)'), f'{language}: all visible text populated')
            check(page.locator('.footer [data-i18n="privacy"]').inner_text() == ('פרטיות' if language == 'he' else 'Privacy'), f'{language}: privacy link translated')
            check(page.locator('.footer [data-i18n="privacy"]').get_attribute('href') == 'https://github.com/didi6135/WhatsAppCallRecorder/blob/main/docs/privacy.md', f'{language}: privacy link has exact public documentation destination')
            for width in [320, 390, 430, 1440]:
                page.set_viewport_size({'width': width, 'height': 900 if width == 1440 else 844})
                for screen in ['record', 'library', 'settings']:
                    page.locator(f'[data-screen="{screen}"]').click()
                    check(page.locator(f'#phone-tab-{screen}').get_attribute('aria-selected') == 'true', f'{language}/{width}/{screen}: selected tab')
                    check(page.locator('#app-screen').get_attribute('aria-labelledby') == f'phone-tab-{screen}', f'{language}/{width}/{screen}: panel label')
                    no_overflow(page, f'{language}/{width}/{screen}')
                page.locator('[data-screen="record"]').click()
                if width in [390, 1440]:
                    page.screenshot(path=str(output / f'{language}-{width}.png'), full_page=True)
                    page.locator('.hero').screenshot(path=str(output / f'{language}-{width}-hero.png'))
            page.set_viewport_size({'width': 390, 'height': 844})
            page.locator('[data-screen="record"]').click()
            page.locator('.mock-record').click()
            check(page.locator('.mock-record').get_attribute('class').endswith('active'), f'{language}: illustration starts')
            check(page.locator('.mock-timer').inner_text() == '00:07', f'{language}: explicitly synthetic timer')
            page.locator('.mock-record').click()
            check('active' not in page.locator('.mock-record').get_attribute('class'), f'{language}: illustration stops')
            page.locator('#phone-tab-record').focus()
            page.keyboard.press('ArrowLeft' if language == 'he' else 'ArrowRight')
            check(page.locator('#phone-tab-library').get_attribute('aria-selected') == 'true', f'{language}: directional keyboard tabs')
            page.keyboard.press('End')
            check(page.locator('#phone-tab-settings').get_attribute('aria-selected') == 'true', f'{language}: End selects final tab')
            page.keyboard.press('Home')
            check(page.locator('#phone-tab-record').get_attribute('aria-selected') == 'true', f'{language}: Home selects first tab')
            page.locator('.activation-details summary').focus()
            page.keyboard.press('Enter')
            check(page.locator('.activation-details').get_attribute('open') is not None, f'{language}: setup details keyboard operable')
            page.locator('.activation-details summary').click()
            for width in [320, 390, 430]:
                page.set_viewport_size({'width': width, 'height': 844})
                page.reload(wait_until='networkidle')
                page.evaluate('''() => {
                  const sizes = Array.from(document.querySelectorAll('body *')).map(node => [node, parseFloat(getComputedStyle(node).fontSize)]);
                  sizes.forEach(([node, size]) => { node.style.fontSize = `${size * 1.35}px`; });
                }''')
                no_overflow(page, f'{language}/{width}/135-percent-fonts')
                if width == 390:
                    page.screenshot(path=str(output / f'{language}-390-large-font.png'), full_page=True)
        page.set_viewport_size({'width': 390, 'height': 844})
        page.reload(wait_until='networkidle')
        page.locator('[data-language="he"]').click()
        page.locator('[data-screen="record"]').click()
        check(page.locator('.mock-record').is_visible(), 'illustration has a real interactive record control')
        check(page.locator('[href^="https://github.com/"]').count() == 4, 'source, privacy, and issue links have real GitHub destinations')

        valid = {'status': 'ready', 'url': 'https://example.test/wa-reco-test.apk', 'version': '1.5.0-test', 'sizeBytes': 12345678, 'sha256': 'a' * 64, 'filename': 'wa-reco-test.apk',
                 'companion': {'url': 'https://example.test/wa-reco-source.zip', 'filename': 'wa-reco-source.zip', 'sizeBytes': 23456789, 'sha256': 'b' * 64},
                 'sourceUrl': 'https://github.com/didi6135/WhatsAppCallRecorder/tree/' + 'c' * 40}
        invalid = [
            {**valid, 'url': 'javascript:alert(1)'},
            {**valid, 'url': 'http://example.test/wa-reco-test.apk'},
            {**valid, 'url': 'https://user:password@example.test/test.apk'},
            {**valid, 'url': 'https://example.test/test.html'},
            {**valid, 'sha256': 'abc'},
            {**valid, 'sizeBytes': -1},
            {**valid, 'filename': '../test.apk'},
            {**valid, 'companion': None},
            {**valid, 'companion': {**valid['companion'], 'sha256': 'abc'}},
            {**valid, 'companion': {**valid['companion'], 'url': 'http://example.test/source.zip'}},
            {**valid, 'companion': {**valid['companion'], 'url': 'https://example.test/source.html'}},
            {**valid, 'sourceUrl': None},
            {**valid, 'sourceUrl': 'https://github.com/didi6135/WhatsAppCallRecorder/tree/main'},
            {**valid, 'sourceUrl': 'https://github.com/another/repo/tree/' + 'c' * 40},
            {'status': 'pending'},
        ]
        for index, value in enumerate(invalid + [valid, {**valid, 'url': 'releases/test.apk'}]):
            page.route('**/release-metadata.json', lambda route, request, value=value: route.fulfill(status=200, content_type='application/json', body=json.dumps(value)))
            page.reload(wait_until='networkidle')
            if index < len(invalid):
                check(page.locator('#apk-download').get_attribute('href') is None, f'metadata case {index}: invalid or pending URL stays disabled')
                check(page.locator('#release-details').is_hidden(), f'metadata case {index}: invalid details hidden')
                check(page.locator('#release-sources').is_hidden(), f'metadata case {index}: invalid companion/source downloads hidden')
            else:
                check(page.locator('#apk-download').get_attribute('href') is not None, f'metadata case {index}: verified schema enables link')
                check(page.locator('#release-hash').inner_text() == 'a' * 64, f'metadata case {index}: exact checksum displayed')
                check(page.locator('#release-version').inner_text() == '1.5.0-test', f'metadata case {index}: exact version displayed')
                check(page.locator('#release-size').inner_text() == '12.3 MB', f'metadata case {index}: decimal MB displayed accurately')
                check(page.locator('#companion-hash').inner_text() == 'b' * 64, f'metadata case {index}: exact ZIP checksum displayed')
                check(page.locator('#companion-download').get_attribute('href') == valid['companion']['url'], f'metadata case {index}: exact companion URL displayed')
                check(page.locator('#release-source').get_attribute('href') == valid['sourceUrl'], f'metadata case {index}: exact public source commit linked')
                if index == len(invalid):
                    page.locator('[data-language="en"]').click()
                    check(page.locator('#apk-download').inner_text() == 'Download Android APK ↓', 'active release translates correctly')
                    page.evaluate("Object.defineProperty(navigator, 'clipboard', {configurable: true, value: {writeText: async value => { window.__copiedHash = value; }}})")
                    page.locator('#copy-hash').click()
                    page.wait_for_function("['File checksum copied.', 'File checksum selected. You can copy it manually.'].includes(document.getElementById('release-status').textContent)")
                    check(page.locator('#release-status').inner_text() in ['File checksum copied.', 'File checksum selected. You can copy it manually.'], 'checksum copy has a truthful completion or fallback')
                    check(page.evaluate('window.__copiedHash') == 'a' * 64, 'checksum copy passes exact public checksum to synthetic clipboard')
                    page.locator('#download').screenshot(path=str(output / 'synthetic-ready-download.png'))
                    page.locator('#release-sources').screenshot(path=str(output / 'synthetic-ready-companion.png'))
            page.unroute('**/release-metadata.json')
        check(not errors, f'no browser errors ({len(errors)})')
        browser.close()

    report = {'checksPassed': len(results), 'checks': results, 'browserErrors': errors, 'scope': 'Static synthetic browser checks only; no APK download, Android/device, OAuth, Telegram, or provider actions.'}
    (output / 'validation.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps({'checksPassed': len(results), 'browserErrors': len(errors), 'output': str(output)}))


if __name__ == '__main__':
    main()
