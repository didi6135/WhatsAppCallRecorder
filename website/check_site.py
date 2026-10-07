"""Bounded local website checks. No phone, provider, or APK download actions."""
import argparse
import json
import re
from pathlib import Path
from urllib.parse import urljoin, urlsplit
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
    served_release = None

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
        # Layout/accessibility baselines always use an explicitly synthetic pending
        # release, even when the served site's real metadata is already ready.
        pending_route = lambda route: route.fulfill(status=200, content_type='application/json', body=json.dumps({'status': 'pending'}))
        context.route('**/release-metadata.json', pending_route)
        page.goto(args.url, wait_until='networkidle')
        check(page.locator('#apk-download').get_attribute('href') is None, 'synthetic pending metadata has no download URL')
        check(page.locator('#release-details').is_hidden(), 'synthetic pending metadata hides release details')
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
        check(page.locator('[href^="https://github.com/"]').count() == 4, 'synthetic pending fixture: source, privacy, and issue links have real GitHub destinations')

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

        # Remove every metadata fixture before checking the actual deployed/local
        # contract. Read only its metadata; never click or fetch APK/ZIP links.
        context.unroute('**/release-metadata.json', pending_route)
        with page.expect_response('**/release-metadata.json') as response_info:
            page.reload(wait_until='networkidle')
        response = response_info.value
        check(response.status == 200, 'actual metadata responds successfully')
        metadata = response.json()
        check(isinstance(metadata, dict) and metadata.get('status') in ['pending', 'ready'], 'actual metadata explicitly declares pending or ready')

        def actual_file(value, extension, label):
            check(isinstance(value, dict), f'actual {label}: file metadata object')
            check(isinstance(value.get('sizeBytes'), int) and not isinstance(value.get('sizeBytes'), bool)
                  and 0 < value['sizeBytes'] <= 536870912, f'actual {label}: bounded integer byte count')
            check(isinstance(value.get('sha256'), str) and re.fullmatch(r'[0-9a-fA-F]{64}', value['sha256']) is not None,
                  f'actual {label}: complete SHA-256')
            raw_url = value.get('url')
            check(isinstance(raw_url, str) and 0 < len(raw_url) <= 2048, f'actual {label}: bounded URL')
            resolved = urljoin(page.url, raw_url)
            parsed = urlsplit(resolved)
            origin = urlsplit(page.url)
            relative = re.match(r'^[A-Za-z][A-Za-z0-9+.-]*:|^//', raw_url) is None
            check(not parsed.username and not parsed.password and not parsed.fragment
                  and parsed.path.lower().endswith(extension)
                  and (parsed.scheme == 'https' or (relative and parsed.scheme == origin.scheme and parsed.netloc == origin.netloc)),
                  f'actual {label}: permitted file destination')
            filename = value.get('filename')
            if 'filename' in value:
                check(isinstance(filename, str) and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,119}\.(apk|zip)', filename) is not None
                      and filename.endswith(extension), f'actual {label}: safe filename')
            return resolved

        if metadata['status'] == 'ready':
            check(isinstance(metadata.get('version'), str) and re.fullmatch(r'[0-9A-Za-z][0-9A-Za-z.+_-]{0,31}', metadata['version']) is not None,
                  'actual ready metadata: bounded version')
            check(isinstance(metadata.get('sourceUrl'), str) and re.fullmatch(r'https://github\.com/didi6135/WhatsAppCallRecorder/tree/[0-9a-f]{40}/?', metadata['sourceUrl']) is not None,
                  'actual ready metadata: exact public source commit')
            apk_url = actual_file(metadata, '.apk', 'APK')
            companion_url = actual_file(metadata.get('companion'), '.zip', 'companion')
            for language in ['he', 'en']:
                page.locator(f'[data-language="{language}"]').click()
                check(page.locator('#apk-download').get_attribute('href') == apk_url, f'actual/{language}: APK URL matches metadata')
                check(page.locator('#apk-download').get_attribute('aria-disabled') == 'false'
                      and page.locator('#apk-download').get_attribute('tabindex') == '0', f'actual/{language}: ready download is accessible')
                check(page.locator('#release-details').is_visible() and page.locator('#release-sources').is_visible(), f'actual/{language}: ready details and source links visible')
                check(page.locator('#release-version').inner_text() == metadata['version'], f'actual/{language}: exact version displayed')
                check(page.locator('#release-hash').inner_text() == metadata['sha256'].lower(), f'actual/{language}: exact APK checksum displayed')
                check(page.locator('#companion-download').get_attribute('href') == companion_url, f'actual/{language}: companion URL matches metadata')
                check(page.locator('#companion-hash').inner_text() == metadata['companion']['sha256'].lower(), f'actual/{language}: exact companion checksum displayed')
                check(page.locator('#release-source').get_attribute('href') == metadata['sourceUrl'], f'actual/{language}: exact source URL displayed')
                for field, selector in [(metadata, '#release-size'), (metadata['companion'], '#companion-size')]:
                    formatted = page.evaluate('''([bytes, language]) => `${new Intl.NumberFormat(language === 'he' ? 'he-IL' : 'en-US', {minimumFractionDigits: 1, maximumFractionDigits: 1}).format(bytes / 1000000)} MB`''', [field['sizeBytes'], language])
                    check(page.locator(selector).inner_text() == formatted, f'actual/{language}/{selector}: published byte count displayed as decimal MB')
                for value, selector in [(metadata, '#apk-download'), (metadata['companion'], '#companion-download')]:
                    check(page.locator(selector).get_attribute('download') == value.get('filename'), f'actual/{language}/{selector}: download filename matches metadata')
                for width in [320, 390, 430, 1440]:
                    page.set_viewport_size({'width': width, 'height': 900 if width == 1440 else 844})
                    no_overflow(page, f'actual/{language}/{width}/ready-release')
                    if width in [390, 1440]:
                        page.locator('#download').screenshot(path=str(output / f'actual-ready-{language}-{width}-download.png'))
                        page.locator('#release-sources').screenshot(path=str(output / f'actual-ready-{language}-{width}-companion.png'))
        else:
            check(page.locator('#apk-download').get_attribute('href') is None
                  and page.locator('#apk-download').get_attribute('aria-disabled') == 'true'
                  and page.locator('#apk-download').get_attribute('tabindex') == '-1', 'actual pending metadata: download remains disabled')
            check(page.locator('#release-details').is_hidden() and page.locator('#release-sources').is_hidden(), 'actual pending metadata: release details and sources hidden')
            check(page.locator('#companion-download').get_attribute('href') is None
                  and page.locator('#release-source').get_attribute('href') is None, 'actual pending metadata: no companion or source URL')
            page.set_viewport_size({'width': 390, 'height': 844})
            page.locator('#download').screenshot(path=str(output / 'actual-pending-download.png'))
        served_release = {'metadataUrl': response.url, 'status': metadata['status'], 'metadata': metadata,
                          'scope': 'Served metadata and DOM correspondence only; APK/ZIP bytes were not downloaded or independently verified.'}
        check(not errors, f'no browser errors ({len(errors)})')
        browser.close()

    report = {'checksPassed': len(results), 'checks': results, 'browserErrors': errors, 'servedRelease': served_release,
              'scope': 'Synthetic browser fixtures plus actual served release-metadata/DOM checks; no APK/ZIP download, Android/device, OAuth, Telegram, or provider actions.'}
    (output / 'validation.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps({'checksPassed': len(results), 'browserErrors': len(errors), 'output': str(output)}))


if __name__ == '__main__':
    main()
