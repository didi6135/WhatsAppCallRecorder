(() => {
  'use strict';
  const messages = {
    he: {
      documentTitle: 'wa-reco — השיחה נשמרת. השליטה אצלך.',
      description: 'wa-reco — הקלטת שיחות WhatsApp במכשירי Android שנבדקו. קבצים מקומיים, קוד פתוח והפעלה ברורה.',
      navigation: 'ניווט ראשי', language: 'שפת האתר', appIllustration: 'המחשה של האפליקציה', illustrationTabs: 'מסכי ההמחשה', flowLabel: 'מה עושים באפליקציה',
      skip: 'דילוג לתוכן', navSetup: 'איך מתחילים', navCompatibility: 'התאמה למכשיר', navSource: 'קוד פתוח ↗',
      eyebrow: 'Android · קוד פתוח · קבצים מקומיים', heroLine1: 'השיחה נשמרת.', heroLine2: 'השליטה אצלך.',
      heroDescription: 'מקליטים, שומרים ומאזינים לשיחות WhatsApp — בשלושה מסכים פשוטים. אתם בוחרים מתי להקליט, והקובץ נשאר במכשיר.',
      heroDownload: 'הורדה ל־Android', heroGuide: 'מדריך ההפעלה ←', heroNote: 'נבדק עם המשתמש על Samsung S26 Ultra / Android 16. בדקו התאמה לפני ההתקנה.',
      tabRecord: 'הקלטה', tabLibrary: 'ההקלטות שלי', tabSettings: 'הגדרות', illustrationCaption: 'המחשה אינטראקטיבית בלבד. אין כאן הקלטה או קובצי שמע.',
      flowRecordTitle: 'מקליטים', flowRecordBody: 'בוחרים מיקרופון או שיחת WhatsApp. מתחילים ועוצרים בלחיצה.',
      flowLibraryTitle: 'שומרים ומאזינים', flowLibraryBody: 'כל ההקלטות במקום אחד, עם האזנה, שיתוף ומחיקה.',
      flowSettingsTitle: 'בודקים מוכנות', flowSettingsBody: 'ההגדרות מציגות מה מוכן ומה צריך אישור או תיקון.',
      setupEyebrow: 'כמה דקות, בפעם הראשונה', setupTitle: 'מההתקנה לשיחה הראשונה.',
      setupIntro: 'הקלטת שיחה דורשת הפעלה מיוחדת ב־Android. האשף באפליקציה מלווה אתכם בכל שלב. לא נדרשים Root, מחשב או אפליקציה נוספת.',
      checkCompatibility: 'בדיקת התאמה למכשיר ←', installTitle: 'מורידים ומתקינים את ה־APK',
      installBody: 'כשההורדה זמינה, פתחו את הקובץ במכשיר Android. אם Android מבקש, אשרו התקנת אפליקציות מהדפדפן או ממנהל הקבצים שבו פתחתם אותו. התקינו ממקור שאתם סומכים עליו.',
      permissionsTitle: 'פותחים ומאשרים את ההרשאות', permissionsBody: 'האשף מבקש גישה למיקרופון ולהתראות. הרשאת גישה להתראות נדרשת לזיהוי שיחות במצב האוטומטי, אם תבחרו להפעיל אותו.',
      activationTitle: 'מפעילים את שירות הקלטת השיחות', activationBody: 'התחברו ל־Wi-Fi ועקבו אחרי אשף ההפעלה: אפשרויות מפתחים, ניפוי באגים אלחוטי, זיווג והפעלת השירות. ההפעלה נותנת לשירות גישה ששיחת WhatsApp דורשת.',
      activationDetails: 'שלבי ההפעלה, בפשטות', developerStep: 'בהגדרות המכשיר: אודות הטלפון ← מידע תוכנה ← לחצו שבע פעמים על מספר גרסת Build. שמות התפריטים עשויים להשתנות.',
      wifiStep: 'פתחו אפשרויות מפתחים והפעילו ניפוי באגים אלחוטי כשאתם מחוברים ל־Wi-Fi.',
      pairStep: 'בזיווג הראשון, פתחו ״זיווג באמצעות קוד״ ב־Android והזינו באשף האפליקציה את הפרטים שהוא מבקש. הקוד זמני; אל תפרסמו אותו.',
      helperStep: 'חזרו לאפליקציה והפעילו את השירות. המשיכו רק כשהאפליקציה מציגה שהוא מחובר ומוכן.',
      firstCallTitle: 'בודקים לפני השיחה', firstCallBody: 'בדקו מוכנות בהגדרות ויידעו את המשתתפים. במסך ההקלטה בחרו ״שיחת WhatsApp״, התחילו להקליט ועברו לשיחה. עצרו בסיום והאזינו לקובץ כדי לוודא ששני הצדדים נשמעים.',
      wifiNoteTitle: 'Wi-Fi להפעלה. לא לכל הקלטה.', wifiNoteBody: 'אחרי שהשירות נותק מתהליך ההפעלה ומציג מוכנות, הוא יכול להמשיך להקליט גם בלי Wi-Fi. אחרי אתחול או אובדן השירות, הפעילו אותו מחדש. לשיחת WhatsApp עצמה עדיין נדרש חיבור לאינטרנט.',
      compatibilityEyebrow: 'מכשירים וגרסאות', compatibilityTitle: 'בודקים התאמה, לפני שמתחילים.', compatibilityIntro: 'מגבלות Android וחומרת המכשיר משפיעות על ההקלטה. בדיקה של מכשיר אחד אינה הבטחה לכל הדגמים.',
      testedBody: 'המשתמש דיווח שהקלטת השיחות עובדת במכשיר שנבדק.', testedTag: 'נבדק עם המשתמש',
      experimentalBody: 'מסלול ניסיוני. דורש בדיקה במכשיר, הפעלה מיוחדת ואימות הקול בקובץ.', experimentalTag: 'ניסיוני', olderTitle: 'Android 13 ומטה', olderBody: 'מיקרופון בלבד. אין במסלול הזה תמיכה בהקלטת שני צדי שיחת WhatsApp באותו מכשיר.', olderTag: 'מיקרופון בלבד',
      iphoneBody: 'אין תמיכה בהקלטת שיחת WhatsApp באותו iPhone.', iphoneTag: 'הקלטת שיחה אינה זמינה', micNote: 'הקלטת מיקרופון רגילה עשויה להיות מושתקת בזמן שיחה. בצעו בדיקה קצרה והאזינו לקובץ לפני שמסתמכים על ההקלטה.',
      backupEyebrow: 'ההקלטות שלכם', backupTitle: 'הקובץ נשאר אצלכם.', backupIntro: 'שמירה והאזנה מקומית זמינות בלי חשבון ענן. אתם מחליטים אם לשתף קובץ או לחבר שירות גיבוי.',
      localTitle: 'קבצים מקומיים', localBody: 'נשמרים באפליקציה. אפשר להאזין, לשתף ולמחוק.', localTag: 'זמין', driveBody: 'החיבור לחשבון ולתיקייה אישיים קיים בקוד. רישום האפליקציה ב־Google ואימות העלאה אמיתית עדיין ממתינים להשלמה.', pendingTag: 'ממתין לאימות',
      telegramBody: 'גיבוי לבוט Telegram פרטי, ישירות מהמכשיר וללא שרת, הוטמע באפליקציה. יוצרים ומחברים בוט אישי בהגדרות ומפעילים גיבוי רק באישורכם. אימות העלאה אמיתית מהמכשיר ל־Telegram עדיין ממתין.',
      downloadEyebrow: 'wa-reco ל־Android', downloadTitle: 'מתחילים עם המכשיר שלכם.', downloadIntro: 'קראו את בדיקת ההתאמה ואת מדריך ההפעלה. יש ליידע את משתתפי השיחה לפני הקלטה.', downloadPending: 'הורדת APK ממתינה לפרסום', sourceButton: 'לקוד ולמדריכים ב־GitHub ↗',
      releasePending: 'קישור ההורדה יופעל אחרי שהגרסה הציבורית וקובץ ההתקנה יאומתו.', releaseVersion: 'גרסה', releaseSize: 'גודל קובץ', copyHash: 'העתקת טביעת קובץ', downloadReady: 'הורדת APK ל־Android ↓', releaseReady: 'קובץ ההתקנה זמין. אפשר להשוות את טביעת SHA-256 לאחר ההורדה.', copyDone: 'טביעת הקובץ הועתקה.', copyFallback: 'טביעת הקובץ סומנה. אפשר להעתיק אותה ידנית.',
      footerNote: 'פרויקט עצמאי וקוד פתוח. אינו מוצר של WhatsApp או Meta.', privacy: 'פרטיות', reportIssue: 'דיווח על בעיה ↗', noScript: 'החלפת שפה והמחשת האפליקציה דורשות JavaScript. מידע ההתקנה וההתאמה מופיע בעמוד; קישור הורדה פעיל טרם אומת.',
      sourcesTitle: 'המקור והרישיונות של אותה גרסה', sourcesBody: 'חבילת המקור המצורפת כוללת הודעות רישוי והנחיות לבנייה מחדש ולהחלפת רכיבי LGPL בהתאם לרישיונות שלהם. הקישורים וטביעות הקבצים מתייחסים לגרסה המוצגת כאן.', companionDownload: 'מקור, רישיונות והנחיות בנייה (.zip) ↓', exactSource: 'המקור המדויק ב־GitHub ↗',
      mockSource: 'מקור ההקלטה', mockMic: 'מיקרופון', mockCall: 'שיחת WhatsApp', mockStart: 'לחצו כדי להתחיל', mockStartBody: 'הקלטה ידנית, בזמן שתבחרו.', mockActive: 'הקלטה לדוגמה', mockActiveBody: 'מצב המחשה בלבד — המיקרופון לא מופעל.', mockRecordButton: 'הדגמת התחלת הקלטה', mockStopButton: 'הדגמת עצירת הקלטה', mockDefaultNote: 'אפשר ללחוץ ולהתנסות בהמחשה.', mockSaved: 'ההמחשה הסתיימה. לא נוצר קובץ.', mockFile: 'הקלטה לדוגמה', mockLocal: 'קובץ מקומי · דוגמה בלבד', mockFilesNote: 'פרטי דוגמה. אין כאן שמע להשמעה.', mockReadiness: 'מוכנות להקלטה', mockChecks: ['מיקרופון', 'התראות', 'גישה להתראות', 'חיבור לשירות', 'הקלטה אוטומטית'], mockReady: 'מוכן', mockSettingsNote: 'תצוגת מוכנות לדוגמה בלבד.'
    },
    en: {
      documentTitle: 'wa-reco — Keep the call. Keep control.', description: 'wa-reco — WhatsApp call recording on tested Android devices. Local files, open source, and a clear setup guide.',
      navigation: 'Main navigation', language: 'Website language', appIllustration: 'App illustration', illustrationTabs: 'Illustrative app screens', flowLabel: 'What you can do in the app',
      skip: 'Skip to content', navSetup: 'Get started', navCompatibility: 'Device compatibility', navSource: 'Open source ↗',
      eyebrow: 'Android · Open source · Local files', heroLine1: 'Keep the call.', heroLine2: 'Keep control.',
      heroDescription: 'Record, save, and listen to WhatsApp calls in three simple screens. You choose when to record, and the file stays on your device.', heroDownload: 'Download for Android', heroGuide: 'Setup guide →', heroNote: 'Tested with the owner on Samsung S26 Ultra / Android 16. Check compatibility before installing.',
      tabRecord: 'Record', tabLibrary: 'Recordings', tabSettings: 'Settings', illustrationCaption: 'Interactive illustration only. No recording or audio files here.',
      flowRecordTitle: 'Record', flowRecordBody: 'Choose the microphone or a WhatsApp call. Start and stop with a tap.', flowLibraryTitle: 'Save and listen', flowLibraryBody: 'All your recordings in one place, with playback, sharing, and deletion.', flowSettingsTitle: 'Check readiness', flowSettingsBody: 'Settings show what is ready and what needs permission or repair.',
      setupEyebrow: 'A few minutes, the first time', setupTitle: 'From installation to your first call.', setupIntro: 'Call recording needs special activation on Android. The in-app wizard guides you through each step. No root, computer, or extra app is required.', checkCompatibility: 'Check device compatibility →',
      installTitle: 'Download and install the APK', installBody: 'When the download is available, open the file on your Android device. If Android asks, allow app installation from the browser or file manager you used to open it. Install from a source you trust.',
      permissionsTitle: 'Open the app and grant permissions', permissionsBody: 'The wizard asks for microphone and notification permissions. Notification access is needed to detect calls in automatic mode, if you choose to enable it.',
      activationTitle: 'Activate the call recording helper', activationBody: 'Connect to Wi-Fi and follow the activation wizard: Developer options, Wireless debugging, pairing, and starting the helper. Activation gives the helper the access needed for WhatsApp calls.', activationDetails: 'Activation steps, simply explained',
      developerStep: 'In device Settings, open About phone → Software information → tap Build number seven times. Menu names may differ by device.', wifiStep: 'Open Developer options and enable Wireless debugging while connected to Wi-Fi.', pairStep: 'For the first pairing, open “Pair device with pairing code” in Android and enter the requested details in the app wizard. The code is temporary; do not publish it.', helperStep: 'Return to the app and start the helper. Continue only when the app shows that it is connected and ready.',
      firstCallTitle: 'Check before your call', firstCallBody: 'Check readiness in Settings and inform the participants. On the recording screen, choose “WhatsApp call,” start recording, and switch to the call. Stop afterward and listen to the file to check that both voices are audible.',
      wifiNoteTitle: 'Wi-Fi for activation. Not for every recording.', wifiNoteBody: 'Once the helper is detached from the activation process and shows ready, it can continue recording without Wi-Fi. Reactivate it after a reboot or if the helper is lost. The WhatsApp call itself still needs Internet access.',
      compatibilityEyebrow: 'Devices and versions', compatibilityTitle: 'Check compatibility before you start.', compatibilityIntro: 'Android restrictions and device hardware affect recording. A test on one device does not guarantee support for every model.',
      testedBody: 'The owner reported that call recording works on the tested device.', testedTag: 'Tested with the owner', experimentalBody: 'Experimental route. Requires device testing, special activation, and checking the recorded audio.', experimentalTag: 'Experimental', olderTitle: 'Android 13 and earlier', olderBody: 'Microphone only. This route does not support recording both sides of a WhatsApp call on the same device.', olderTag: 'Microphone only', iphoneBody: 'Recording a WhatsApp call on the same iPhone is not supported.', iphoneTag: 'Call recording unavailable', micNote: 'Ordinary microphone recording may be silenced during a call. Make a short test and listen to the file before relying on a recording.',
      backupEyebrow: 'Your recordings', backupTitle: 'The file stays with you.', backupIntro: 'Local saving and playback work without a cloud account. You decide whether to share a file or connect a backup service.', localTitle: 'Local files', localBody: 'Saved in the app. Listen, share, or delete them.', localTag: 'Available', driveBody: 'Personal account and folder connection is implemented. Google app registration and verification of a real upload are still pending.', pendingTag: 'Verification pending', telegramBody: 'Private-bot Telegram backup is implemented, directly from the device with no server. Create and connect your own bot in Settings, then explicitly enable backup. Verification of a real device upload to Telegram is still pending.',
      downloadEyebrow: 'wa-reco for Android', downloadTitle: 'Start with your device.', downloadIntro: 'Read the compatibility notes and setup guide. Inform the call participants before recording.', downloadPending: 'APK download awaiting release', sourceButton: 'Code and guides on GitHub ↗', releasePending: 'The download link will become active after the public release and installation file are verified.', releaseVersion: 'Version', releaseSize: 'File size', copyHash: 'Copy file checksum', downloadReady: 'Download Android APK ↓', releaseReady: 'The installation file is available. Compare the SHA-256 checksum after downloading.', copyDone: 'File checksum copied.', copyFallback: 'File checksum selected. You can copy it manually.',
      footerNote: 'An independent open-source project. Not a WhatsApp or Meta product.', privacy: 'Privacy', reportIssue: 'Report an issue ↗', noScript: 'The language switch and app illustration need JavaScript. Setup and compatibility information is on this page; an active download has not yet been verified.',
      sourcesTitle: 'Source and licenses for this release', sourcesBody: 'The companion source package includes license notices and instructions for rebuilding and replacing LGPL components under their licenses. These links and checksums refer to the release shown here.', companionDownload: 'Source, notices & rebuild instructions (.zip) ↓', exactSource: 'Exact release source on GitHub ↗',
      mockSource: 'Recording source', mockMic: 'Microphone', mockCall: 'WhatsApp call', mockStart: 'Tap to start', mockStartBody: 'Manual recording, when you choose.', mockActive: 'Recording illustration', mockActiveBody: 'Illustration only — the microphone is not active.', mockRecordButton: 'Illustrate starting a recording', mockStopButton: 'Illustrate stopping a recording', mockDefaultNote: 'Tap to try the illustration.', mockSaved: 'Illustration ended. No file was created.', mockFile: 'Sample recording', mockLocal: 'Local file · Illustration only', mockFilesNote: 'Sample details. No audio is available here.', mockReadiness: 'Recording readiness', mockChecks: ['Microphone', 'Notifications', 'Notification access', 'Helper connection', 'Automatic recording'], mockReady: 'Ready', mockSettingsNote: 'Illustrative readiness display only.'
    }
  };

  let language = 'he';
  let screen = 'record';
  let demoRecording = false;
  let demoStopped = false;
  let release = null;
  const get = id => document.getElementById(id);
  const t = key => messages[language][key];
  const element = (tag, className, text) => {
    const result = document.createElement(tag);
    if (className) result.className = className;
    if (text !== undefined) result.textContent = text;
    return result;
  };

  function renderScreen() {
    const panel = get('app-screen');
    panel.replaceChildren();
    get('screen-subtitle').textContent = t({record: 'tabRecord', library: 'tabLibrary', settings: 'tabSettings'}[screen]);
    panel.setAttribute('aria-labelledby', `phone-tab-${screen}`);
    document.querySelectorAll('[data-screen]').forEach(tab => {
      const selected = tab.dataset.screen === screen;
      tab.setAttribute('aria-selected', String(selected));
      tab.tabIndex = selected ? 0 : -1;
    });
    if (screen === 'record') {
      panel.append(element('p', 'mock-source-label', t('mockSource')));
      const source = element('div', 'mock-source');
      source.append(element('span', '', t('mockMic')), element('span', '', t('mockCall')));
      const card = element('div', 'mock-card');
      card.append(element('h3', '', t(demoRecording ? 'mockActive' : 'mockStart')), element('p', '', t(demoRecording ? 'mockActiveBody' : 'mockStartBody')));
      const button = element('button', `mock-record${demoRecording ? ' active' : ''}`);
      button.type = 'button';
      button.setAttribute('aria-label', t(demoRecording ? 'mockStopButton' : 'mockRecordButton'));
      button.addEventListener('click', () => {
        demoStopped = demoRecording;
        demoRecording = !demoRecording;
        renderScreen();
        panel.querySelector('button').focus({preventScroll: true});
      });
      const timer = element('p', 'mock-timer', demoRecording ? '00:07' : '00:00');
      timer.dir = 'ltr';
      card.append(button, timer, element('p', '', t(demoStopped ? 'mockSaved' : 'mockDefaultNote')));
      panel.append(source, card);
    } else if (screen === 'library') {
      const list = element('div', 'mock-list');
      ['00:24', '00:41', '01:12'].forEach((duration, index) => {
        const file = element('div', 'mock-file');
        const title = element('b', '', `${t('mockFile')} ${String(index + 1).padStart(2, '0')}`);
        file.append(title, element('p', '', `${duration} · ${t('mockLocal')}`));
        const icons = element('div', 'mock-file-icons', '▷  ↗  ×');
        icons.setAttribute('aria-hidden', 'true');
        file.append(icons);
        list.append(file);
      });
      panel.append(list, element('p', 'mock-note', t('mockFilesNote')));
    } else {
      const settings = element('div', 'mock-settings');
      settings.append(element('h3', '', t('mockReadiness')));
      t('mockChecks').forEach(label => {
        const row = element('div', 'mock-check');
        row.append(element('span', '', label), element('span', '', `✓ ${t('mockReady')}`));
        settings.append(row);
      });
      settings.append(element('p', 'mock-settings-footer', t('mockSettingsNote')));
      panel.append(settings);
    }
  }

  function renderRelease() {
    const link = get('apk-download');
    get('release-details').hidden = !release;
    get('release-sources').hidden = !release;
    link.textContent = t(release ? 'downloadReady' : 'downloadPending');
    get('release-status').textContent = t(release ? 'releaseReady' : 'releasePending');
    link.classList.toggle('is-disabled', !release);
    link.setAttribute('aria-disabled', String(!release));
    link.tabIndex = release ? 0 : -1;
    if (!release) {
      link.removeAttribute('href');
      link.removeAttribute('download');
      get('companion-download').removeAttribute('href');
      get('release-source').removeAttribute('href');
      return;
    }
    link.href = release.url;
    if (release.filename) link.setAttribute('download', release.filename);
    get('release-version').textContent = release.version;
    get('release-size').textContent = `${(release.sizeBytes / 1000000).toLocaleString(language === 'he' ? 'he-IL' : 'en-US', {minimumFractionDigits: 1, maximumFractionDigits: 1})} MB`;
    get('release-hash').textContent = release.sha256;
    const companion = get('companion-download');
    companion.href = release.companion.url;
    if (release.companion.filename) companion.setAttribute('download', release.companion.filename);
    get('companion-size').textContent = `${(release.companion.sizeBytes / 1000000).toLocaleString(language === 'he' ? 'he-IL' : 'en-US', {minimumFractionDigits: 1, maximumFractionDigits: 1})} MB`;
    get('companion-hash').textContent = release.companion.sha256;
    get('release-source').href = release.sourceUrl;
  }

  function setLanguage(next) {
    if (!(next in messages)) return;
    language = next;
    document.documentElement.lang = language;
    document.documentElement.dir = language === 'he' ? 'rtl' : 'ltr';
    document.title = t('documentTitle');
    document.querySelector('meta[name="description"]').content = t('description');
    document.querySelectorAll('[data-i18n]').forEach(node => {
      const value = t(node.dataset.i18n);
      if (typeof value === 'string') node.textContent = value;
    });
    document.querySelectorAll('[data-i18n-aria]').forEach(node => node.setAttribute('aria-label', t(node.dataset.i18nAria)));
    document.querySelectorAll('[data-language]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.language === language)));
    try { localStorage.setItem('wa-reco-language', language); } catch (_) { /* Preference is optional. */ }
    renderScreen();
    renderRelease();
  }

  function validateFile(value, extension) {
    if (!value || typeof value !== 'object' || !Number.isSafeInteger(value.sizeBytes) || value.sizeBytes <= 0 || value.sizeBytes > 536870912
      || typeof value.sha256 !== 'string' || !/^[0-9a-fA-F]{64}$/.test(value.sha256) || typeof value.url !== 'string' || value.url.length > 2048) return null;
    let url;
    try { url = new URL(value.url, window.location.href); } catch (_) { return null; }
    const localRelative = !/^[A-Za-z][A-Za-z0-9+.-]*:|^\/\//.test(value.url) && url.origin === window.location.origin;
    if (url.username || url.password || url.hash || !url.pathname.toLowerCase().endsWith(extension) || (url.protocol !== 'https:' && !(localRelative && url.protocol === window.location.protocol))) return null;
    if (value.filename !== undefined && (typeof value.filename !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,119}\.(apk|zip)$/.test(value.filename) || !value.filename.endsWith(extension))) return null;
    return {url: url.href, sizeBytes: value.sizeBytes, sha256: value.sha256.toLowerCase(), filename: value.filename};
  }
  function validateRelease(value) {
    if (!value || value.status !== 'ready' || typeof value.version !== 'string' || !/^[0-9A-Za-z][0-9A-Za-z.+_-]{0,31}$/.test(value.version)
      || typeof value.sourceUrl !== 'string' || !/^https:\/\/github\.com\/didi6135\/WhatsAppCallRecorder\/tree\/[0-9a-f]{40}\/?$/.test(value.sourceUrl)) return null;
    const apk = validateFile(value, '.apk');
    const companion = validateFile(value.companion, '.zip');
    return apk && companion ? {...apk, version: value.version, companion, sourceUrl: value.sourceUrl} : null;
  }

  document.querySelectorAll('[data-language]').forEach(button => button.addEventListener('click', () => setLanguage(button.dataset.language)));
  const tabs = Array.from(document.querySelectorAll('[data-screen]'));
  function selectTab(tab, focus = false) {
    screen = tab.dataset.screen;
    renderScreen();
    if (focus) tab.focus();
  }
  tabs.forEach((tab, index) => {
    tab.addEventListener('click', () => selectTab(tab));
    tab.addEventListener('keydown', event => {
      let next;
      if (event.key === 'Home') next = 0;
      else if (event.key === 'End') next = tabs.length - 1;
      else if (event.key === 'ArrowRight') next = (index + (language === 'he' ? -1 : 1) + tabs.length) % tabs.length;
      else if (event.key === 'ArrowLeft') next = (index + (language === 'he' ? 1 : -1) + tabs.length) % tabs.length;
      else return;
      event.preventDefault();
      selectTab(tabs[next], true);
    });
  });
  get('copy-hash').addEventListener('click', async () => {
    if (!release) return;
    try {
      await navigator.clipboard.writeText(release.sha256);
      get('release-status').textContent = t('copyDone');
    } catch (_) {
      const selection = window.getSelection();
      if (selection) {
        const range = document.createRange();
        range.selectNodeContents(get('release-hash'));
        selection.removeAllRanges();
        selection.addRange(range);
      }
      get('release-status').textContent = t('copyFallback');
    }
  });
  try {
    const saved = localStorage.getItem('wa-reco-language');
    if (saved === 'he' || saved === 'en') language = saved;
  } catch (_) { /* Hebrew is the default. */ }
  setLanguage(language);
  fetch('release-metadata.json', {cache: 'no-store'})
    .then(response => response.ok ? response.json() : null)
    .then(value => { release = validateRelease(value); renderRelease(); })
    .catch(() => { release = null; renderRelease(); });
})();
