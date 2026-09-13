# Complete OEM meter inventory (category 0x03)

All 95 XML entries are included once. “UI” means an explicit branch in the OEM `setMeterVehicleSettingData`; it does not mean runtime vehicle support. Route is XML InfoFrom source/menu. The main report explains model limits. Options, API-to-encoded values and original Japanese comments are in [machine-readable inventory](instrument-evidence/meter-ui-inventory.json).

| ID | OEM UI title, or translated XML comment if no title | Route | UI branch | XML line | UI source line |
|---|---|---|---|---:|---:|
| 0x01 | Speed Alarm 1 Function | 0x50/0x01 | Defined | 1403 | 4507 |
| 0x02 | Speed Alarm 1 Vehicle Speed Setting | 0x50/0x01 | Defined | 1425 | 4531 |
| 0x03 | Speed Alarm 2 Function | 0x50/0x01 | Defined | 1444 | 4551 |
| 0x04 | Speed Alarm 2 Vehicle Speed Setting | 0x50/0x01 | Defined | 1466 | 4575 |
| 0x05 | ACC set-speed display units | None | No Data | 1485 | 4497 |
| 0x06 | Night Vision: Light Control | None | Defined | 1505 | 4595 |
| 0x07 | Night Vision: System Start-Up Settings | None | Defined | 1527 | 4619 |
| 0x08 | HUD Light Control | None | Defined | 1547 | 4643 |
| 0x09 | Language Selection — US | 0x50/0x02 | Defined | 1564 | 4663 |
| 0x0A | Language Selection — US alternate | None | Defined | 1587 | 4693 |
| 0x0B | Meter display language switch | None | No Data | 1608 | 4497 |
| 0x0C | Language Selection — China | 0x50/0x03 | Defined | 1622 | 4723 |
| 0x0D | Language Selection — EU 6-language | 0x50/0x04 | Defined | 1644 | 4753 |
| 0x0E | Language Selection — South American Portuguese | 0x50/0x23 | Defined | 1671 | 4783 |
| 0x0F | Language Selection — EU 12-language | 0x50/0x1F | Defined | 1693 | 4813 |
| 0x10 | Language Selection — EU 13-language | 0x50/0x24 | Defined | 1726 | 4843 |
| 0x11 | Language Selection — Russia | 0x50/0x22 | Defined | 1760 | 4873 |
| 0x12 | Warning Message | 0x50/0x05 | Defined | 1782 | 4903 |
| 0x13 | Adjust Outside Temp. Display | 0x50/0x06 | Defined | 1804 | 4927 |
| 0x14 | Adjust Outside Temp. Display | 0x50/0x07 | Defined | 1831 | 4957 |
| 0x15 | Trip A & Average Fuel Economy A Refueling Interlock Reset | 0x50/0x08 | Defined | 1862 | 4987 |
| 0x16 | Trip A & Average Power Consumption A Charging Interlock Reset | None | Defined | 1884 | 5011 |
| 0x17 | Change Reset Conditions for Elapsed Time | 0x50/0x09 | Defined | 1904 | 5035 |
| 0x18 | "Trip A" Reset Timing (non-PHEV) | 0x50/0x1A | Defined | 1927 | 5059 |
| 0x19 | "Trip B" Reset Timing (non-PHEV) | 0x50/0x1B | Defined | 1950 | 5089 |
| 0x1A | "Trip A" Reset Timing (PHEV) | 0x50/0x17 | Defined | 1973 | 5119 |
| 0x1B | "Trip B" Reset Timing (PHEV) | 0x50/0x18 | Defined | 1997 | 5149 |
| 0x1C | Select Drive Computer Display | None | Defined | 2021 | 5179 |
| 0x1D | Adjust Alarm Volume | 0x50/0x0B | Defined | 2044 | 5203 |
| 0x1E | Compass Zone Settings | None | Defined | 2067 | 5227 |
| 0x1F | WELCOME Scroll Duration | None | Defined | 2087 | 5247 |
| 0x20 | Initial Brightness Setting for Welcome Lights | None | Defined | 2108 | 5271 |
| 0x21 | Initial Brightness Setting for Welcome Lights | None | Defined | 2128 | 5291 |
| 0x22 | Welcome Light Settings | None | Defined | 2145 | 5311 |
| 0x23 | Shift Up Backlight | 0x50/0x11 | Defined | 2165 | 5335 |
| 0x24 | ECO Indicator ON/OFF | 0x50/0x12 | Defined | 2187 | 5359 |
| 0x25 | Change Units for Digital Speedometer Display | None | Defined | 2209 | 5383 |
| 0x26 | Power-meter units | 0x50/0x14 | No Data | 2230 | 4497 |
| 0x27 | Stopwatch Function Settings | None | Defined | 2253 | 5407 |
| 0x28 | Fuel Efficiency Backlight | 0x50/0x16 | Defined | 2274 | 5431 |
| 0x29 | Fuel Efficiency Backlight Color | 0x50/0x32 | Defined | 2296 | 5461 |
| 0x2A | Fuel Efficiency Backlight Color | 0x50/0x33 | Defined | 2323 | 5485 |
| 0x2B | CLOCK Function | None | Defined | 2351 | 5509 |
| 0x2C | CLOCK Function | None | Defined | 2365 | 5529 |
| 0x2D | Timed Charging Function | None | Defined | 2386 | 5553 |
| 0x2E | Timed Charging Mode | None | Defined | 2406 | 5577 |
| 0x2F | Scheduled charging start hour | None | No Data | 2426 | 4497 |
| 0x30 | Scheduled charging start minute | None | No Data | 2447 | 4497 |
| 0x31 | Scheduled charging end hour | None | No Data | 2468 | 4497 |
| 0x32 | Scheduled charging end minute | None | No Data | 2489 | 4497 |
| 0x33 | SIL display settings | None | Defined | 2510 | 5601 |
| 0x34 | Hold ECON Mode | 0x50/0x19 | Defined | 2530 | 5625 |
| 0x35 | Smart Start Guidance Display | 0x50/0x1C | Defined | 2552 | 5649 |
| 0x36 | Speed Display Unit | 0x50/0x1D | Defined | 2574 | 5679 |
| 0x37 | Auto Engine Idle Stop Guidance Screens | 0x50/0x1E | Defined | 2596 | 5703 |
| 0x38 | Turn By Turn Auto Display | 0x50/0x20 | Defined | 2618 | 5733 |
| 0x39 | HUW function on/off (acronym unresolved) | None | No Data | 2640 | 4497 |
| 0x3A | Change Display Unit for HUD Digital Speedometer | None | Defined | 2660 | 5763 |
| 0x3B | Turn By Turn Auto Display | 0x50/0x31 | Defined | 2680 | 5787 |
| 0x3C | Language Selection — Arabic | 0x50/0x21 | Defined | 2702 | 5817 |
| 0x3D | Display contents selection | None | No Data | 2724 | 4497 |
| 0x3E | Favorites selection | None | No Data | 2738 | 4497 |
| 0x3F | Contents Change Sound ON/OFF | None | Defined | 2752 | 5847 |
| 0x40 | New Message Notifications | 0x50/0x37 | Defined | 2772 | 5871 |
| 0x41 | Select Wallpaper | None | Defined | 2794 | 5895 |
| 0x42 | Load image | None | No Data | 2816 | 4497 |
| 0x43 | Delete Wallpaper | None | Defined | 2830 | 5919 |
| 0x44 | Select Drive Computer Display | None | Defined | 2851 | 5943 |
| 0x45 | Opening Animation | None | Defined | 2874 | 5967 |
| 0x46 | Background Color | None | Defined | 2894 | 5991 |
| 0x47 | Keyless Start Guidance Screens | None | Defined | 2916 | 6015 |
| 0x48 | Turn by Turn Display | None | Defined | 2936 | 6045 |
| 0x49 | Auto Engine Idle Stop Guidance Screens | None | Defined | 2956 | 6069 |
| 0x4A | Warning Message | None | Defined | 2976 | 6099 |
| 0x4B | Traffic Sign Recognition System Display | None | Defined | 2996 | 6123 |
| 0x4C | Display language variant 1 | None | No Data | 3016 | 4497 |
| 0x4D | Display language variant 2 | None | No Data | 3037 | 4497 |
| 0x4E | Display language variant 3 | None | No Data | 3057 | 4497 |
| 0x4F | Display language variant 4 | None | No Data | 3077 | 4497 |
| 0x50 | Default | None | Defined | 3108 | 6147 |
| 0x51 | Always show gear when not using paddles | 0x50/0x0A | No Data | 3122 | 4497 |
| 0x52 | Gear Position Display | 0x50/0x36 | Defined | 3144 | 6171 |
| 0x53 | Turn-signal tone | 0x50/0x0D | Defined | 3166 | 6195 |
| 0x54 | Reverse Alert Tone | 0x50/0x0C | Defined | 3189 | 6219 |
| 0x55 | Tire Angle Monitor | 0x50/0x35 | Defined | 3211 | 6243 |
| 0x56 | Display Speed Unit | 0x50/0x0E | Defined | 3233 | 6267 |
| 0x57 | Speed/Distance Units | 0x50/0x2F | Defined | 3255 | 6297 |
| 0x58 | Auto Interior Illumination | 0x50/0x26 | Defined | 3277 | 6321 |
| 0x59 | IDS setting | None | No Data | 3303 | 4497 |
| 0x5A | Trip-computer display units | 0x50/0x13 | No Data | 3325 | 4497 |
| 0x5B | Meter display configuration | 0x50/0x15 | No Data | 3348 | 4497 |
| 0x5C | Tachometer | 0x50/0x2E | Defined | 3371 | 6345 |
| 0x5D | Scheduled charging start time | None | No Data | 3393 | 4497 |
| 0x5E | Scheduled charging end time | None | No Data | 3413 | 4497 |
| 0x5F | Change Display Language 5 | None | Defined | 3433 | 6369 |
