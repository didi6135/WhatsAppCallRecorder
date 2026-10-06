import React, { useState } from 'react';
import { Alert, Linking, Platform, StyleSheet, Text, TextInput, TouchableOpacity, View } from 'react-native';
import { useRecording } from '../context/RecordingContext';
import { WaveMark } from './Visuals';
import { colors, ui } from '../theme';
import { SystemAccessStatus } from '../services/NativeRecorder';
import { phoneSetupStep } from '../setup/readiness';

const messageOf = (failure: unknown) => failure instanceof Error ? failure.message : 'הפעולה לא הושלמה. נסו שוב.';
const readPort = (value: string): number | null => {
  if (!/^\d{1,5}$/.test(value.trim())) return null;
  const port = Number(value);
  return port >= 1 && port <= 65535 ? port : null;
};

export default function SystemSetupWizard({ compact = false, focused = false, onShowDetails, accessOverride, onStatusChanged }: {
  compact?: boolean; focused?: boolean; onShowDetails?: () => void;
  accessOverride?: SystemAccessStatus | null; onStatusChanged?: () => Promise<void>;
}) {
  const { systemAccessStatus: storedAccess, deviceInfo, isBusy, isRecording, prepareSystemPairing,
    pairSystemRecorder, connectSystemRecorder, openSystemAccessSetup, refreshSystemAccessStatus } = useRecording();
  const access = accessOverride ?? storedAccess;
  const [showManualPairing, setShowManualPairing] = useState(false);
  const [showManualConnection, setShowManualConnection] = useState(false);
  const [showSetupDetails, setShowSetupDetails] = useState(false);
  const [pairingPort, setPairingPort] = useState('');
  const [pairingCode, setPairingCode] = useState('');
  const [connectionPort, setConnectionPort] = useState('');
  const [localError, setLocalError] = useState<string | null>(null);
  const pairingSetup = access.pairingSetup;
  const developerEnabled = access.developerOptionsEnabled ?? access.wirelessDebuggingEnabled;
  const canPair = developerEnabled && access.wirelessDebuggingEnabled;
  const disabled = isBusy || isRecording || access.connecting || pairingSetup?.pairing === true;
  const compatibilityText = deviceInfo && (deviceInfo.sdk === 34 || deviceInfo.sdk === 35)
    ? 'התמיכה ב־Android 14/15 ניסיונית. הקלטת שני צדדי השיחה וההקלטה האוטומטית עדיין ממתינות לבדיקה במכשיר אמיתי בגרסאות האלה. אם המערכת אינה מאפשרת את החיבור, אפשר להשתמש בהקלטה ידנית מהמיקרופון.'
    : deviceInfo && deviceInfo.sdk > 36
      ? 'גרסת Android הזו עדיין לא נבדקה במכשיר אמיתי. ההתאמה תלויה בהרשאות וביכולות של הטלפון; אפשר להשתמש בהקלטה ידנית מהמיקרופון אם החיבור אינו זמין.'
      : null;

  const openSetup = async (destination: 'about' | 'developer' | 'wireless') => {
    try { await openSystemAccessSetup(destination); }
    catch (failure) { setLocalError(messageOf(failure)); }
  };

  const beginPairing = async () => {
    setLocalError(null);
    try {
      await prepareSystemPairing();
      await openSystemAccessSetup('wireless');
    } catch (failure) {
      setLocalError(messageOf(failure));
      setShowManualPairing(true);
    }
  };

  const showPairingInstructions = () => Alert.alert(
    'נחבר את הטלפון לאפליקציה',
    'במסך ההגדרות שייפתח:\n1. אם נפתח ״אפשרויות מפתח״, לחצו ״ניפוי באגים אלחוטי״ והפעילו אותו.\n2. לחצו ״צימוד מכשיר באמצעות קוד צימוד״.\n3. השאירו את חלון הקוד פתוח.\n4. משכו את וילון ההתראות, לחצו על התראת המקליט והזינו בה את שש הספרות.\n\nאחרי אישור הצימוד חזרו לאפליקציה.',
    [
      { text: 'ביטול', style: 'cancel' },
      { text: 'המשך להגדרות', onPress: () => { void beginPairing(); } },
    ],
  );

  const pairManually = async () => {
    const port = readPort(pairingPort);
    if (port === null || !/^\d{6}$/.test(pairingCode)) {
      setLocalError('הזינו את המספר שבסוף כתובת הצימוד ואת קוד הצימוד בן שש הספרות.');
      return;
    }
    setLocalError(null);
    try {
      await pairSystemRecorder(port, pairingCode);
      setPairingCode('');
      await refreshSystemAccessStatus();
      await onStatusChanged?.();
    } catch (failure) { setLocalError(messageOf(failure)); }
  };

  const connect = async (manual: boolean) => {
    const port = manual ? readPort(connectionPort) : 0;
    if (port === null) {
      setLocalError('הזינו את המספר שבסוף הכתובת במסך הראשי של ״ניפוי באגים אלחוטי״.');
      return;
    }
    setLocalError(null);
    try {
      await connectSystemRecorder(port);
      await refreshSystemAccessStatus();
      await onStatusChanged?.();
    } catch (failure) {
      setLocalError(messageOf(failure));
      setShowManualConnection(true);
    }
  };

  const statusText = access.connecting ? 'מחבר את רכיב ההקלטה…'
    : access.helperConnected ? 'רכיב ההקלטה פעיל — אפשר להקליט גם בלי Wi-Fi'
    : pairingSetup?.pairing ? 'מאשר את קוד הצימוד עם הטלפון…'
      : access.paired ? 'הצימוד נשמר. נותר לחבר את רכיב ההקלטה.'
        : pairingSetup?.active && pairingSetup.localPortAvailable ? 'חלון הצימוד נמצא. הזינו את שש הספרות בהתראת המקליט.'
          : pairingSetup?.active && pairingSetup.discovering ? 'ממתין לפתיחת חלון קוד הצימוד בהגדרות הטלפון…'
            : pairingSetup?.active ? 'הצימוד מוכן. פתחו את חלון קוד הצימוד בטלפון.'
        : access.wirelessDebuggingEnabled ? 'ההגדרה בטלפון מופעלת. נותר לאשר את הצימוד.'
          : 'הקלטת שיחות דורשת הגדרה קצרה בטלפון.';
  const pairingError = !access.paired || pairingSetup?.active ? pairingSetup?.error : null;
  const errorText = localError || pairingError || access.error;

  if (focused) {
    const step = phoneSetupStep(accessOverride === null ? null : access);
    const text = {
      checking: ['בודקים את אפשרויות החיבור', 'נבדוק את מצב הטלפון לפני שנציג את הפעולה הבאה.'],
      ready: ['חיבור ההקלטה פעיל', 'החיבור אומת. אפשר להמשיך גם בלי Wi-Fi ובלי להשאיר את הגדרת החיבור האלחוטי מופעלת.'],
      unsupported: ['הקלטת שיחות אינה זמינה במכשיר הזה', 'חיבור השיחות דורש Android 14 ומעלה. התמיכה ב־Android 14/15 ניסיונית ועדיין ממתינה לבדיקה במכשיר אמיתי. אפשר להמשיך עם הקלטה ידנית מהמיקרופון.'],
      developer: ['פותחים אפשרויות נוספות בטלפון', 'לחצו על הכפתור. ב־Samsung בחרו ״פרטי תוכנה״, לחצו שבע פעמים על ״מספר Build״ ואשרו את קוד הנעילה אם התבקשתם. חזרו לכאן כדי שנבדוק שההגדרה הופעלה.'],
      wireless: ['מאפשרים את החיבור הראשוני', 'התחברו לרשת Wi-Fi. במסך שייפתח הפעילו ״ניפוי באגים אלחוטי״ ואשרו את הרשת. אם נפתח ״אפשרויות מפתח״, גללו אל אותה הגדרה. אחרי האישור חזרו לאפליקציה.'],
      pair: ['מאשרים עם שש ספרות', 'נפתח את חלון הצימוד בטלפון. השאירו אותו פתוח, משכו את וילון ההתראות והזינו את הקוד בן שש הספרות בהתראת המקליט. אחרי האישור חזרו לכאן.'],
      connect: ['מפעילים את חיבור ההקלטה', 'אישור החיבור כבר נשמר. כשהטלפון מחובר ל־Wi-Fi והחיבור האלחוטי מופעל, נוכל להפעיל את רכיב ההקלטה. לאחר ההפעלה אפשר להמשיך גם בלי Wi-Fi.'],
    }[step];
    return <View style={styles.card}>
      <Text style={styles.title}>{text[0]}</Text>
      <Text style={[styles.body, { marginTop: 12 }]}>{text[1]}</Text>
      {compatibilityText && <Text style={styles.small}>{compatibilityText}</Text>}
      {step === 'developer' && <TouchableOpacity style={[styles.primaryButton, disabled && styles.disabled]} onPress={() => { void openSetup('about'); }} disabled={disabled} accessibilityRole="button"><Text style={styles.primaryText}>פתיחת אודות הטלפון</Text></TouchableOpacity>}
      {step === 'wireless' && <TouchableOpacity style={[styles.primaryButton, disabled && styles.disabled]} onPress={() => { void openSetup('wireless'); }} disabled={disabled} accessibilityRole="button"><Text style={styles.primaryText}>פתיחת הגדרת החיבור</Text></TouchableOpacity>}
      {step === 'checking' && <TouchableOpacity style={styles.secondaryButton} onPress={() => { void onStatusChanged?.(); }} accessibilityRole="button"><Text style={styles.secondaryText}>בדיקה נוספת</Text></TouchableOpacity>}
      {step === 'pair' && <>
        {pairingSetup?.active && <Text style={styles.small} accessibilityLiveRegion="polite">{pairingSetup.pairing ? 'מאשר את הקוד…' : pairingSetup.localPortAvailable ? 'חלון הצימוד נמצא. אפשר להזין את הקוד בהתראה.' : 'ממתין לפתיחת חלון קוד הצימוד בטלפון.'}</Text>}
        <TouchableOpacity style={[styles.primaryButton, disabled && styles.disabled]} onPress={showPairingInstructions} disabled={disabled} accessibilityRole="button"><Text style={styles.primaryText}>פתיחת חלון קוד הצימוד</Text></TouchableOpacity>
        <TouchableOpacity style={styles.textButton} onPress={() => setShowManualPairing(value => !value)} disabled={disabled} accessibilityRole="button"><Text style={styles.link}>{showManualPairing ? 'סגירת ההזנה הידנית' : 'ההתראה לא הופיעה? הזנה ידנית'}</Text></TouchableOpacity>
      </>}
      {step === 'connect' && <>
        <TouchableOpacity style={[styles.primaryButton, disabled && styles.disabled]} onPress={() => { void connect(false); }} disabled={disabled} accessibilityRole="button"><Text style={styles.primaryText}>{access.connecting ? 'מפעיל את החיבור…' : 'הפעלת חיבור ההקלטה'}</Text></TouchableOpacity>
        <TouchableOpacity style={styles.textButton} onPress={() => setShowManualConnection(value => !value)} disabled={disabled} accessibilityRole="button"><Text style={styles.link}>{showManualConnection ? 'סגירת מספר החיבור' : 'החיבור לא נמצא? הזנת מספר ידנית'}</Text></TouchableOpacity>
        {showManualConnection && <View style={styles.fallback}>
          <Text style={styles.body}>במסך הראשי של ״ניפוי באגים אלחוטי״ מופיעה כתובת. הזינו רק את המספר אחרי ״:״. זהו מספר החיבור, לא המספר מחלון קוד הצימוד.</Text>
          <TouchableOpacity style={styles.secondaryButton} onPress={() => { void openSetup('wireless'); }} disabled={disabled} accessibilityRole="button"><Text style={styles.secondaryText}>הצגת מספר החיבור בטלפון</Text></TouchableOpacity>
          <TextInput style={styles.input} value={connectionPort} onChangeText={value => setConnectionPort(value.replace(/\D/g, ''))} keyboardType="number-pad" maxLength={5} editable={!disabled} placeholder="מספר החיבור" placeholderTextColor={colors.muted} accessibilityLabel="המספר בסוף כתובת החיבור" />
          <TouchableOpacity style={[styles.primaryButton, disabled && styles.disabled]} onPress={() => { void connect(true); }} disabled={disabled} accessibilityRole="button"><Text style={styles.primaryText}>חיבור עם המספר שהזנתי</Text></TouchableOpacity>
        </View>}
        {errorText && <TouchableOpacity style={styles.textButton} onPress={showPairingInstructions} disabled={disabled} accessibilityRole="button"><Text style={styles.link}>הטלפון ביטל את האישור? צימוד מחדש</Text></TouchableOpacity>}
        {(pairingSetup?.active || showManualPairing) && <TouchableOpacity style={styles.textButton} onPress={() => setShowManualPairing(value => !value)} disabled={disabled} accessibilityRole="button"><Text style={styles.link}>{showManualPairing ? 'סגירת ההזנה הידנית' : 'ההתראה לא הופיעה? הזנה ידנית'}</Text></TouchableOpacity>}
      </>}
      {(step === 'pair' || step === 'connect') && showManualPairing && <View style={styles.fallback}>
        <Text style={styles.body}>השאירו את חלון הקוד פתוח בהגדרות לצד המקליט במסך מפוצל. במסך האפליקציות האחרונות לחצו על סמל האפליקציה ובחרו ״מסך מפוצל״.</Text>
        <Text style={styles.label}>המספר אחרי ״:״ בכתובת הצימוד</Text>
        <TextInput style={styles.input} value={pairingPort} onChangeText={value => setPairingPort(value.replace(/\D/g, ''))} keyboardType="number-pad" maxLength={5} editable={!disabled} placeholder="מספר הצימוד" placeholderTextColor={colors.muted} accessibilityLabel="המספר בסוף כתובת הצימוד" />
        <Text style={styles.label}>קוד הצימוד בן שש הספרות</Text>
        <TextInput style={styles.input} value={pairingCode} onChangeText={value => setPairingCode(value.replace(/\D/g, ''))} keyboardType="number-pad" maxLength={6} editable={!disabled} secureTextEntry placeholder="שש הספרות" placeholderTextColor={colors.muted} accessibilityLabel="קוד הצימוד" />
        <TouchableOpacity style={[styles.primaryButton, disabled && styles.disabled]} onPress={() => { void pairManually(); }} disabled={disabled} accessibilityRole="button"><Text style={styles.primaryText}>אישור הקוד</Text></TouchableOpacity>
      </View>}
      {errorText && <Text style={styles.error} accessibilityLiveRegion="polite">{localError?.startsWith('הזינו') ? localError : 'הפעולה לא הושלמה. ודאו שחיבור ה־Wi-Fi פעיל ונסו שוב. אפשר להשתמש בהזנה הידנית אם החיבור לא נמצא.'}</Text>}
    </View>;
  }

  if (!access.available) {
    return (
      <View style={styles.card}>
        <Text style={styles.title}>הפעלת הקלטת שיחות</Text>
        <Text style={styles.body}>{Platform.OS !== 'android' ? 'הפעלת הקלטת שיחות בדרך הזו זמינה בגרסת Android בלבד.' : deviceInfo ? 'החיבור דורש Android 14 ומעלה. ב־Android 14/15 התמיכה ניסיונית ועדיין ממתינה לבדיקה במכשיר אמיתי. אפשר להמשיך בהקלטה ידנית מהמיקרופון.' : 'בודק את אפשרויות ההקלטה בטלפון…'}</Text>
        <Text style={styles.small}>הנתיב הזה אינו זמין ב־iPhone.</Text>
        {errorText && <Text style={styles.error}>{errorText}</Text>}
      </View>
    );
  }

  if (compact && onShowDetails) {
    return <View style={styles.card}>
      <View style={styles.heading}><WaveMark size={38} /><Text style={styles.title}>חיבור קצר, ואז אפשר להקליט</Text></View>
      <Text style={styles.body}>{access.connecting ? 'מחבר את רכיב ההקלטה…' : access.paired ? 'הצימוד כבר נשמר. מפעילים את הרכיב בחיבור ל-Wi-Fi וממשיכים גם בלעדיו.' : 'נעבור יחד על ההגדרות שהטלפון צריך. הכול מתבצע כאן, בלי אפליקציה נוספת.'}</Text>
      {compatibilityText && <Text style={styles.small}>{compatibilityText}</Text>}
      <TouchableOpacity style={[styles.primaryButton, disabled && styles.disabled]} onPress={access.paired && canPair ? () => { void connect(false); } : onShowDetails} disabled={disabled} accessibilityRole="button"><Text style={styles.primaryText}>{access.connecting ? 'מחבר…' : access.paired && canPair ? 'הפעלת רכיב ההקלטה' : 'להגדרה המונחית'}</Text></TouchableOpacity>
      {errorText && <Text style={styles.error} accessibilityLiveRegion="polite">{errorText}</Text>}
    </View>;
  }

  return (
    <View style={styles.card}>
      <View style={styles.heading}><WaveMark size={38} /><Text style={styles.title}>הפעלת הקלטת שיחות</Text></View>
      {!access.helperConnected && <View style={styles.progress}>{[developerEnabled, access.wirelessDebuggingEnabled, access.paired].map((complete, index) => <View key={index} style={[styles.progressStep, complete && styles.progressComplete]} />)}</View>}
      <Text style={[styles.status, access.helperConnected && styles.ready]} accessibilityLiveRegion="polite">{statusText}</Text>
      {compatibilityText && <Text style={styles.small}>{compatibilityText}</Text>}
      {!compact && <Text style={styles.body}>{access.helperConnected
        ? 'אם רכיב ההקלטה מפסיק לפעול, התחברו ל-Wi-Fi והפעילו אותו שוב.'
        : 'להפעלת רכיב ההקלטה צריך להתחבר ל-Wi-Fi ולהפעיל ניפוי באגים אלחוטי. לאחר שהרכיב פעיל אפשר להקליט גם בלי Wi-Fi.'}</Text>}
      {!access.helperConnected && <Text style={styles.body}>הכול מתבצע בטלפון. אין צורך במחשב או בהתקנת אפליקציה נוספת.</Text>}
      {!access.helperConnected && <>
        {developerEnabled && <Text style={styles.completed}>✓ אפשרויות מפתח מופעלות</Text>}
        {access.wirelessDebuggingEnabled && <Text style={styles.completed}>✓ החיבור האלחוטי מופעל</Text>}
        {(!developerEnabled || (!compact && showSetupDetails)) && <>
        <Text style={styles.step}>1. פותחים תפריט נוסף בטלפון</Text>
        <Text style={styles.body}>אם ״אפשרויות מפתח״ כבר מופיע בהגדרות, עברו לשלב 2. אחרת: הכפתור הבא פותח ״אודות הטלפון״. ב־Samsung בחרו ״פרטי תוכנה״ ולחצו שבע פעמים על ״מספר Build״. אשרו את קוד הנעילה אם הטלפון מבקש, ואז חזרו לאפליקציה.</Text>
        <TouchableOpacity style={[styles.secondaryButton, disabled && styles.disabled]} onPress={() => { void openSetup('about'); }} disabled={disabled} accessibilityRole="button"><Text style={styles.secondaryText}>פתיחת אודות הטלפון</Text></TouchableOpacity>
        </>}
        {(developerEnabled && !access.wirelessDebuggingEnabled || (!compact && showSetupDetails)) && <>
        <Text style={styles.step}>2. מאפשרים את החיבור בטלפון</Text>
        <Text style={styles.body}>התחברו לרשת Wi-Fi. הכפתור הבא פותח את הגדרות החיבור או את ״אפשרויות מפתח״. אם נפתח תפריט ״אפשרויות מפתח״, גללו ולחצו ״ניפוי באגים אלחוטי״. הפעילו את המתג, אשרו את החיבור לרשת שלכם ואז חזרו לכאן לשלב 3.</Text>
        <TouchableOpacity style={[styles.secondaryButton, disabled && styles.disabled]} onPress={() => { void openSetup('wireless'); }} disabled={disabled} accessibilityRole="button"><Text style={styles.secondaryText}>פתיחת ההגדרה בטלפון</Text></TouchableOpacity>
        </>}
        {canPair && !access.paired && <>
        <Text style={styles.step}>3. מאשרים בעזרת קוד קצר</Text>
        <Text style={styles.body}>לחצו על הכפתור הבא. תקבלו הוראות והתראה שבה מזינים את הקוד שהטלפון מציג. השאירו את חלון קוד הצימוד פתוח בזמן הזנת הקוד בהתראה.</Text>
        </>}
        {!compact && developerEnabled && <TouchableOpacity style={styles.textButton} onPress={() => setShowSetupDetails(!showSetupDetails)} accessibilityRole="button"><Text style={styles.link}>{showSetupDetails ? 'הסתרת השלבים שכבר הושלמו' : 'הצגת פרטי השלבים שהושלמו'}</Text></TouchableOpacity>}
      </>}
      {!access.paired && canPair && <TouchableOpacity style={[styles.primaryButton, disabled && styles.disabled]} onPress={showPairingInstructions} disabled={disabled} accessibilityRole="button"><Text style={styles.primaryText}>{pairingSetup?.pairing ? 'מאשר את הצימוד…' : 'הצג הוראות והפעל צימוד'}</Text></TouchableOpacity>}
      {access.paired && !access.helperConnected && <>
        {canPair && <>
        <TouchableOpacity style={[styles.primaryButton, disabled && styles.disabled]} onPress={() => { void connect(false); }} disabled={disabled} accessibilityRole="button"><Text style={styles.primaryText}>{access.connecting ? 'מחבר…' : 'חיבור להקלטה'}</Text></TouchableOpacity>
        <Text style={styles.small}>אם הטלפון ביטל את האישור או שהחיבור לא מצליח, אפשר לאשר אותו שוב.</Text>
        <TouchableOpacity style={[styles.secondaryButton, disabled && styles.disabled]} onPress={showPairingInstructions} disabled={disabled} accessibilityRole="button"><Text style={styles.secondaryText}>צימוד מחדש</Text></TouchableOpacity>
        </>}
      </>}
      {compact && onShowDetails && <TouchableOpacity style={styles.textButton} onPress={onShowDetails} accessibilityRole="button"><Text style={styles.link}>הוראות צעד אחר צעד</Text></TouchableOpacity>}
      {!compact && !access.helperConnected && <>
        <TouchableOpacity style={styles.textButton} onPress={() => setShowManualPairing(!showManualPairing)} accessibilityRole="button"><Text style={styles.link}>{showManualPairing ? 'סגירת ההזנה הידנית' : 'ההתראה לא הופיעה? אפשר להזין ידנית'}</Text></TouchableOpacity>
        {showManualPairing && <View style={styles.fallback}>
          <Text style={styles.step}>הזנת קוד כשהחלון נשאר פתוח</Text>
          <Text style={styles.body}>פתחו את המקליט ואת הגדרות הטלפון במסך מפוצל: במסך האפליקציות האחרונות לחצו על סמל האפליקציה ובחרו ״פתח בתצוגת מסך מפוצל״. בחלק השני פתחו ״צימוד מכשיר באמצעות קוד צימוד״. סגירת חלון הקוד מבטלת אותו.</Text>
          <TouchableOpacity style={[styles.secondaryButton, disabled && styles.disabled]} onPress={() => { void openSetup('wireless'); }} disabled={disabled} accessibilityRole="button"><Text style={styles.secondaryText}>פתיחת הגדרות החיבור בטלפון</Text></TouchableOpacity>
          <Text style={styles.label}>המספר שבסוף כתובת הצימוד</Text>
          <Text style={styles.small}>העתיקו רק את המספר שאחרי הסימן ״:״ בחלון קוד הצימוד.</Text>
          <TextInput style={styles.input} value={pairingPort} onChangeText={value => setPairingPort(value.replace(/\D/g, ''))} keyboardType="number-pad" maxLength={5} editable={!disabled} placeholder="מספר הצימוד" placeholderTextColor="#777" accessibilityLabel="המספר בסוף כתובת הצימוד" />
          <Text style={styles.label}>קוד הצימוד בן שש הספרות</Text>
          <TextInput style={styles.input} value={pairingCode} onChangeText={value => setPairingCode(value.replace(/\D/g, ''))} keyboardType="number-pad" maxLength={6} editable={!disabled} secureTextEntry placeholder="קוד בן שש ספרות" placeholderTextColor="#777" accessibilityLabel="קוד הצימוד" />
          <TouchableOpacity style={[styles.primaryButton, disabled && styles.disabled]} onPress={() => { void pairManually(); }} disabled={disabled} accessibilityRole="button"><Text style={styles.primaryText}>אישור הצימוד</Text></TouchableOpacity>
          <TouchableOpacity style={styles.textButton} onPress={() => { void Linking.openSettings().catch(failure => setLocalError(messageOf(failure))); }} accessibilityRole="button"><Text style={styles.link}>פתיחת הרשאת ההתראות של האפליקציה</Text></TouchableOpacity>
        </View>}
        {access.paired && <TouchableOpacity style={styles.textButton} onPress={() => setShowManualConnection(!showManualConnection)} accessibilityRole="button"><Text style={styles.link}>{showManualConnection ? 'סגירת פרטי החיבור' : 'החיבור לא נמצא? הזינו את מספר החיבור'}</Text></TouchableOpacity>}
        {access.paired && showManualConnection && <View style={styles.fallback}>
          <Text style={styles.body}>במסך הראשי של ״ניפוי באגים אלחוטי״ מופיעה כתובת ומספר. הזינו כאן את המספר שאחרי ״:״. זהו מספר החיבור, והוא שונה מהמספר בחלון קוד הצימוד.</Text>
          <TextInput style={styles.input} value={connectionPort} onChangeText={value => setConnectionPort(value.replace(/\D/g, ''))} keyboardType="number-pad" maxLength={5} editable={!disabled} placeholder="מספר החיבור" placeholderTextColor="#777" accessibilityLabel="המספר בסוף כתובת החיבור" />
          <TouchableOpacity style={[styles.primaryButton, disabled && styles.disabled]} onPress={() => { void connect(true); }} disabled={disabled} accessibilityRole="button"><Text style={styles.primaryText}>חיבור עם המספר שהזנתי</Text></TouchableOpacity>
        </View>}
      </>}
      {errorText && <Text style={styles.error} accessibilityLiveRegion="polite">{errorText}</Text>}
      {!compact && <Text style={styles.small}>אחרי אתחול הטלפון או הפעלה מחדש של האפליקציה ייתכן שתצטרכו להפעיל שוב את הרכיב בחיבור ל-Wi-Fi. סטטוס ״פעיל״ מופיע רק כשהאפליקציה אימתה את החיבור לרכיב.</Text>}
    </View>
  );
}

const styles = StyleSheet.create({
  card: { ...ui.card, padding: 20, marginBottom: 14 },
  heading: { flexDirection: 'row-reverse', alignItems: 'center', gap: 12, marginBottom: 18 },
  title: { ...ui.label, fontSize: 17, flex: 1 },
  status: { ...ui.body, fontSize: 14, color: colors.amber, marginBottom: 8 },
  ready: { color: colors.green },
  completed: { color: colors.green, fontSize: 13, textAlign: 'right', writingDirection: 'rtl', marginBottom: 8 },
  body: { ...ui.body, fontSize: 14, marginBottom: 12 },
  small: { ...ui.subtitle, fontSize: 12, marginBottom: 8 },
  step: { ...ui.label, marginTop: 16, marginBottom: 8 },
  primaryButton: { ...ui.primaryButton, marginVertical: 8 },
  primaryText: ui.primaryText,
  secondaryButton: { ...ui.secondaryButton, marginVertical: 6 },
  secondaryText: ui.secondaryText,
  textButton: { minHeight: 48, paddingVertical: 14, justifyContent: 'center' },
  link: { color: colors.green, fontSize: 13, fontWeight: '600', textAlign: 'right', writingDirection: 'rtl' },
  disabled: { opacity: 0.4 },
  fallback: { backgroundColor: colors.background, borderRadius: 18, padding: 16, marginTop: 6 },
  label: { ...ui.label, fontSize: 14, marginTop: 10, marginBottom: 8 },
  input: { minHeight: 54, borderWidth: 1, borderColor: '#BACBC3', borderRadius: 14, backgroundColor: colors.surface, paddingHorizontal: 16, fontSize: 16, color: colors.ink, textAlign: 'right', writingDirection: 'ltr', marginBottom: 10 },
  error: { ...ui.warning, marginVertical: 8 },
  progress: { flexDirection: 'row-reverse', gap: 6, marginBottom: 18 },
  progressStep: { flex: 1, height: 4, borderRadius: 4, backgroundColor: colors.border },
  progressComplete: { backgroundColor: colors.green },
});
