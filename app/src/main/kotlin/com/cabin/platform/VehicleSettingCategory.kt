package com.cabin.platform

/** Presentation only: protocol-local field IDs never become command IDs here. */
internal enum class VehicleSettingCategory {
    DOORS, LIGHTS, MIRRORS, PARKING, COMFORT, ASSISTANCE, INSTRUMENTS, AUDIO, CHARGING, SERVICE, OTHER;

    companion object {
        fun action(action: FytVehicleAction): VehicleSettingCategory = when (action) {
            FytVehicleAction.RESET_HONDA_AMPLIFIER -> AUDIO
            FytVehicleAction.INITIALIZE_PANORAMA -> PARKING
            FytVehicleAction.CALIBRATE_COMPASS -> INSTRUMENTS
            else -> SERVICE
        }

        fun control(control: SyuFactoryControl): VehicleSettingCategory = when (control) {
            SyuFactoryControl.HONDA_REVERSE_TONE -> PARKING
            SyuFactoryControl.HONDA_TRIP_A_RESET, SyuFactoryControl.HONDA_TRIP_B_RESET -> SERVICE
            else -> when (control.group) {
                SyuFactoryGroup.HONDA_PANEL -> INSTRUMENTS
                SyuFactoryGroup.CAMERA, SyuFactoryGroup.PARKING -> PARKING
                SyuFactoryGroup.MIRRORS -> MIRRORS
                SyuFactoryGroup.AMBIENT -> LIGHTS
                SyuFactoryGroup.CHARGING -> CHARGING
                SyuFactoryGroup.SEAT_MEMORY -> COMFORT
            }
        }

        fun reading(profile: Int, row: FytSyuReading): VehicleSettingCategory {
            val id = row.viewId
            return when (row.screen) {
                "honda_factory_media", "honda_amplifier_2023" -> AUDIO
                "honda_specialized" -> if (row.options.isNotEmpty()) AUDIO else INSTRUMENTS
                "honda_legacy" -> if (id == 8) INSTRUMENTS else AUDIO
                "honda_compass_2023" -> INSTRUMENTS
                "honda_panorama_2023", "honda_rzc_camera_2023" -> PARKING
                "honda_0298" -> when (id) {
                    58, 59 -> SERVICE
                    61, 62, 63, 73, 80 -> LIGHTS
                    in 64..69, 71, 72, 79, 81, 108, 110, 111 -> DOORS
                    70, 82, 112, 113 -> COMFORT
                    83, 84, 85, 86, 114, 149 -> ASSISTANCE
                    109 -> PARKING
                    60, 74, 75, 76, 77, 78, 87, 150 -> INSTRUMENTS
                    else -> OTHER
                }
                "honda_wc_settings_2023" -> when (id) {
                    47, 85, 88, 93, 94, 95, in 105..108, 112, 113 -> PARKING
                    in 49..53 -> LIGHTS
                    in 54..60, 86, 87, 89, 90, 100 -> DOORS
                    in 61..64, 96, 97, 99, 101 -> ASSISTANCE
                    70, 71 -> SERVICE
                    91, 92 -> AUDIO
                    98 -> COMFORT
                    48, in 65..69, 72, 102, 109, 110, 111 -> INSTRUMENTS
                    else -> OTHER
                }
                "honda_rzc_settings_2023" -> when (id) {
                    109, in 155..159, 196 -> PARKING
                    153, 161, 173 -> COMFORT
                    166, 176, 190, 191 -> DOORS
                    177 -> MIRRORS
                    192 -> LIGHTS
                    151, 178 -> INSTRUMENTS
                    152, 154, 175, 179, in 193..195, 197 -> ASSISTANCE
                    else -> OTHER
                }
                "gm_wc_0036" -> when (id) {
                    20, 21, 24, 26, 38 -> COMFORT
                    22, 23, 25 -> MIRRORS
                    in 27..42, 70, 137 -> DOORS
                    43, 44, 138 -> LIGHTS
                    else -> OTHER
                }
                "honda_accord_xp" -> accord(id)
                "honda_accord_xbs" -> if (profile == 262) accord(id) else when (id) {
                    51, 52 -> SERVICE
                    54, 55, 56 -> LIGHTS
                    57, 58, 59, 63 -> DOORS
                    60, 64 -> COMFORT
                    in 66..70 -> ASSISTANCE
                    83 -> PARKING
                    53, 61, 62, 80, 81, 82 -> INSTRUMENTS
                    else -> OTHER
                }
                "honda_accord_wc" -> when (id) {
                    4, 32, 33, 34, 64 -> PARKING
                    24, 25 -> DOORS
                    26, 27 -> LIGHTS
                    28, 30 -> SERVICE
                    1, 3, 18, 29 -> INSTRUMENTS
                    else -> OTHER
                }
                "golf_wc_2023", "golf_rzc_2023" -> when (id) {
                    in CabinGolfLighting.fields, in CabinGolfAmbient.fields -> LIGHTS
                    in CabinGolfUnits.fields(profile) -> INSTRUMENTS
                    in CabinGolfHybrid.fields(profile) -> CHARGING
                    in 51..55 -> MIRRORS
                    in 19..25, 194, 234, 335, 336, 337 -> PARKING
                    39, 40, 41, 145, 146, 340, 341, 342, 368, 369 -> DOORS
                    in 56..64, 85, 200 -> INSTRUMENTS
                    in CabinGolfRzcCharging.fields(profile) -> CHARGING
                    else -> OTHER
                }
                "ford_0334" -> when {
                    id in CabinFordAmplifier.fields || id in CabinFordMedia.fields -> AUDIO
                    id in CabinFordSeats.fields -> COMFORT
                    CabinFordSettings.supports(profile) && id in CabinFordSettings.fields -> when (id) {
                        135 -> PARKING
                        in 133..145 -> if (id == 138) COMFORT else if (id == 140) DOORS else ASSISTANCE
                        in 146..151 -> LIGHTS
                        153, in 167..171 -> MIRRORS
                        152, in 154..160, 165, 166 -> DOORS
                        161, 162, 163, 164 -> COMFORT
                        in 172..174 -> INSTRUMENTS
                        else -> OTHER
                    }
                    else -> when (id) {
                        28, 42, 132, 180 -> INSTRUMENTS
                        38, 90, 187 -> ASSISTANCE
                        39, 40, 63 -> INSTRUMENTS
                        43, 60, 61, 62 -> PARKING
                        44, 45, 57, 58, 64, 66, 70, 73, 188 -> LIGHTS
                        47, 71, 189 -> MIRRORS
                        65, 67, 69 -> DOORS
                        68, 72 -> COMFORT
                        else -> OTHER
                    }
                }
                else -> OTHER
            }
        }

        private fun accord(id: Int) = when (id) {
            18, 19 -> SERVICE
            22, 23, 24 -> LIGHTS
            25, 26, 27, 28 -> DOORS
            60 -> PARKING
            20, 21, 29, 30, 31, 32, 34 -> INSTRUMENTS
            else -> OTHER
        }
    }
}
