# Honda Civic — original OEM settings investigation

**Implementation update:** [Honda Customizer 2.0](../../honda-customizer/README.md) now implements the traced vehicle, panel, camera and service paths. Its release includes the standalone APK and 67 passing tests. Head-unit preferences and diagnostics integrate Honda's installed screens. Hardware testing remains outstanding; the research boundaries below still apply.

**This report concerns Honda Civic and original Honda/Mitsubishi Electric software.** The investigation covers the supplied `1.F197.60` package and tenth-generation Civic documentation, including Brazil 2020 and separately identified North American/Type R variants. It is not a claim to cover every Civic generation or validate every option on the 2020 EXL.

## Start here

- [Civic Brazil 2020 feature index](civic-br-2020-index.md): 35 vehicle, instrument, camera and language feature concepts checked against Civic documentation. The [JSON](civic-br-2020-index.json) preserves candidate OEM IDs, options and routes.
- [Instrument-panel implementation](instrument-settings.md): language, tachometer, content editing and preset selection, including the separate app missed by the initial XML-only search.
- [Other tenth-generation Civic variants](other-civic-variants.md): Civic-specific evidence for units, North American cluster language, Sensing preferences and sport-model options.
- [Original head-unit system and camera settings](system-and-camera-settings.md): separate controls outside the vehicle XML.

These lists separate **a feature documented on Civic** from **the exact OEM command binding for a specific vehicle**. Similar names in the shared catalog establish candidates, not confirmed bindings. Multiple regional IDs are alternatives, not multiple controls available on one car.

## Newly established code paths

### Cluster language

The supplied OEM code implements a South American Portuguese selector: category `03`, setting `0E`, source `50`, menu `23`.

| Choice | OEM API value | Encoded setting value |
|---|---:|---:|
| Português | 1 | `00` |
| English | 2 | `01` |

This follows the XML value table, the stock Settings dispatch branch, the original resource array and the UI's index conversion. The exact trace is in [language evidence](../stock-language-review.md). This proves the stock implementation; it does not independently prove that a particular 2020 EXL exposes the same selector.

The original head-unit language control is separate: `ChangeLanguageActivity` maps Brazilian Portuguese to `pt_BR` and calls UnitInfoManager. Android/head-unit locale and cluster setting values must not be treated as the same command namespace.

### Instrument-panel contents and presets

The OEM Settings app launches `com.mitsubishielectric.ada.app.externaldisplay.CustomizeMeter`. Its supporting classes implement adding, deleting and ordering content, and selecting three presets. This is a real implementation outside the generic Meter Setup list.

The separate preset screen explicitly finds category `03`, ID `5B`, and changes it through VehicleInfoManager. Therefore the generic screen's “No Data” branch for `03/5B` **does not mean the feature is unimplemented**. See the [source-level editor trace](instrument-panel-editor.md) for the content-service calls, callback behavior and preset-number handling. Its XML source/menu is `50/15`; these fields are not a standalone bus-frame recipe.

### Vehicle actions and diagnostics

Maintenance reset, TPMS calibration and customization reset have dedicated OEM service paths. Lower-level diagnostic read/clear interfaces also exist. These are catalogued in [vehicle settings and actions](vehicle-settings-and-actions.md). The diagnostic declarations alone do not establish a Civic ECU address, an OBD PID or a validated OBD command sequence.

## What the package can and cannot identify

The original archive contains **40 model configuration files**, beyond the initially extracted system folder. An included base part number matches a part listed for 2017 Civic in [Honda bulletin 16-100](https://static.nhtsa.gov/odi/tsbs/2017/SB-10108289-9340.pdf). This establishes a Civic part-family connection; the bulletin describes a different software revision and does not certify this package for Brazil 2020.

The shared customization XML contains **313 entries**, including equipment outside the confirmed Civic feature list. Its 313 model-filter containers are all empty. The software obtains actual supported category/ID pairs through runtime vehicle capability responses. Neither 313 total entries nor 167 entries with route metadata is a Civic feature count. [Full package/model audit](model-applicability.md).

## Supporting audit files — not Civic-only feature lists

These preserve all inspected shared-software evidence for reproducibility. Their presence here does not promote unrelated model features into Civic support:

- [95 shared meter entries](instrument-inventory.md), with [options and UI branches](instrument-evidence/meter-ui-inventory.json).
- [218 shared non-meter entries](non-meter-catalog-inventory.json).
- [103 vehicle-service methods](vehicle-service-api-inventory.json) and [237 lower-level interface signatures](cpu-service-api-signatures.json).
- [Complete original 313-entry XML export](../complete-settings-catalog.json).

The Civic index is reproducible with `python3 honda-cluster-analysis/oem-civic/build_civic_index.py` from the workspace root. It resolves every candidate against the original exported catalog and keeps code mapping separate from manual applicability.

**Research result:** the earlier search missed separate OEM applications and model files. This audit adds those paths and a Civic-specific evidence filter. It does not establish an exhaustive inventory for unexamined Civic generations. The subsequent implementation is linked above; no vehicle commands were sent during development.
