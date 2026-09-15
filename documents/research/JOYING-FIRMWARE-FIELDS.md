# Supplied Joying firmware field selection

The com.syu.ms APK SHA-256 `4b428302e29c9e2503ccf7844a127f5bed59450736317629aa42b5eb35a9b577` must not select the newer public Civic UI layout.

In its `module/canbus/v.M2`, `pswitch_bfb` includes profile `0x4012a` (262442) and falls through to `cond_c57`. That common branch publishes A/C at 24, fan at 29 and recirculation at 21. Door packet branch `pswitch_aab` publishes 36–41. `f0/wp.g0` passes these IDs directly into `i1/v.s`; it does not translate them into the newer UI's 11/21 field layout.

The fingerprint selects a separate canonical Joying layout containing the checked Boolean climate/door fields and fan range. Motion, temperature scaling and maintenance fields are left unmapped here. Unknown APK fingerprints do not select either Civic layout. This verifies the supplied APK's decoder, not the installed head unit's firmware or physical output.
