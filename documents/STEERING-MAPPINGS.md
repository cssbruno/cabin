# Button mappings

Open Car settings → Audio and steering → Steering shortcuts. Choose short or long press, select **Assign a button**, choose a built-in action or installed app, and press a supported button. Existing rows support **Change action** and **Remove mapping**. Changes save automatically. Long presses use a 650 ms hold threshold and run on release; learning a long-press mapping only requires identifying the button once.

Built-in actions include Cabin launcher, Back, projection playback/track/assistant controls, climate, dashboard pages, volume, mute and Do nothing. App targets use the existing exported launcher-activity picker and are validated again at launch. An unavailable app reports a status message.

Mappings work only for events delivered to Cabin's foreground Activity: F1–F12, generic gamepad buttons 1–16, programme-color buttons, supported media keys and Search. Home, Power, Back as an input, volume as an input, and call keys remain outside the existing input allowlist. Back and volume are available as target actions. These Cabin shortcuts are separate from factory learning below; arbitrary app targets remain foreground-only.

Short/long mappings are independent. Reassigning the same press replaces its previous target. Canceled presses and focus loss do not execute queued actions. Reset removes Cabin mappings for both press types, while retaining profiles, app shortcuts and factory learning.

The existing settings backup/restore includes app mappings in schema 4. Schemas 1–3 remain readable; old backups contain no app mappings. Imports validate component syntax, key allowlists, duplicates and conflicts before writes. Restored app targets still require an installed, enabled, permitted launcher activity before they can run.

Validation: focused router/persistence/backup tests and steering editor UI tests. No head-unit or CAN-bus validation has been performed.

## Factory steering learning

Open **Factory steering learning** in the same panel, while parked. Cabin binds to SYU Toolkit module 10 and subscribes to fields 0–6. Opening the screen never clears or starts learning. Commands use the vendor one-way Binder protocol, on an owned worker thread.

- **Wired / ADC:** start detection, hold a physical button until a fresh signal appears, then select one of the 13 verified factory actions. Repeat and choose Finish and save. Stop detection sends no Save, but cannot undo assignments already sent.
- **MCU / panel:** available only when the service reports key learning enabled. Start, choose an action, then press the physical button. Supports 25 direct targets and 20 custom slots with 45 factory functions. These namespaces are validated separately.
- **Clear factory learning:** separate confirmation, outside a learning session. This can immediately remove existing factory mappings. MCU clear also affects ADC/default/long-action groups in this firmware.
- MCU Finish also saves. Closing, losing foreground, and the 60-second learning timeout send that same finish operation; the firmware has no verified cancel without saving. ADC cleanup stops detection without requesting Save.

Learned feedback is distinct from durable storage. Save is displayed as **Save requested**: the service gives no MCU storage acknowledgement. Test the result on the head unit. Assignments require matching callback values to leave the pending state, time out after 10 seconds, and are never replayed on reconnect. ADC readings expire after 3 seconds; sentinel 50 is not learnable. Old connection callbacks and commands are rejected. Factory tables are excluded from Cabin JSON backups.

The protocol is verified against Joying com.syu.ms 2.23.0711.1001 (SHA-256 `4b428302e29c9e2503ccf7844a127f5bed59450736317629aa42b5eb35a9b577`) and its matching steering client. ADC custom click/long commands 7/10/11 are not exposed because their action namespace and initial table synchronization differ from MCU custom functions. Factory action availability remains firmware-dependent. Hardcoded CAN decoder actions are not universally remapped by these commands; arbitrary apps and custom short/long targets use the Cabin editor.

Automated coverage includes real Parcel encoding with a one-way fake Binder, command ordering, matching feedback, signal expiry, disconnect/reconnect epochs, pending/session timeouts, explicit clearing, namespace bounds, and parked/confirmation UI gates. Hardware operation and persistence across a head-unit reboot still require device validation.
