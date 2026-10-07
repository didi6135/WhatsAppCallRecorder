# Personal Telegram recording backup

Each installer connects a bot they own. No maintainer bot, token, shared chat or recording relay server is included in the app. Drive and Telegram connections and upload queues are independent. Local recording is available without either cloud provider.

## Connect and enable

1. Open Settings in wa-reco and find Telegram backup.
2. Open Telegram's official [BotFather](https://t.me/BotFather), create a **new dedicated bot** using `/newbot`, and copy the token. A bot already used by a webhook or another integration is unsuitable.
3. Paste the token into the masked field in wa-reco. The app validates the bot and creates a temporary connection link. The token is not added to the recording list, public status or language storage.
4. Open that link in Telegram and press Start in the bot's private chat. Return to wa-reco and confirm the connection. The unique temporary connection code binds the chat; a group or channel is rejected.
5. Enable backup of **existing and future completed recordings**. Connecting alone does not upload audio. Check the destination before enabling because recordings can contain other people's private information.

The bot token grants control of the bot. Keep it private; revoke a compromised token through BotFather and reconnect. The app stores its connection credentials encrypted with an Android Keystore key in private installation storage excluded from Android backup.

## Files, network and recovery

The original finalized WAV remains on the phone. Telegram's cloud Bot API accepts documents up to50MB; wa-reco sends parts no larger than48,000,000bytes. Every part has a PCM WAV header and whole audio frames, so it plays independently without lossy compression. Part filenames include the recording ID and sequence. All parts together represent the full original PCM data. Telegram may apply its own retention/account/service rules; bot chats are cloud chats, not end-to-end encrypted Secret Chats.

Uploads need an Internet connection and run through Android's scheduled work. No Wi-Fi-only upload restriction is imposed; recording-helper activation and cloud upload are separate functions. Android battery/background policies can delay work.

A recording is marked uploaded after every part has a validated Telegram receipt matching the bot, private chat, filename and byte count. Known rejected/retryable requests may be retried. If the server may have accepted a part but the app lacks a valid receipt, the app stops automatic retries and shows an **unknown outcome**. Check the bot chat before confirming a retry, which may create a duplicate. Already confirmed parts are retained rather than resent.

Disable backup to stop new scheduled uploads. Disconnect removes the active bot credentials and stops its queue; it does not remove local WAVs or already sent Telegram messages. The encrypted receipt ledger is retained so reconnecting to the same destination does not silently resend confirmed parts. An upload already accepted by Telegram cannot be recalled by disconnecting. Reconnect and explicitly enable again when appropriate.

## Verification boundary

Protocol, part planning, queue and UI checks use synthetic data. These checks do not prove real Bot API pairing, upload delivery, account access or background behavior on another phone. Test with a disclosed short non-private recording and verify the files in your own bot chat before relying on backup.

Official service documentation: [Bot API](https://core.telegram.org/bots/api), [Bot deep links](https://core.telegram.org/bots/features#deep-linking), [Telegram privacy](https://telegram.org/privacy).
