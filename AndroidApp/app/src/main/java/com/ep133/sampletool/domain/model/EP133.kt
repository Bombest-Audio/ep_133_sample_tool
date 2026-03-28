package com.ep133.sampletool.domain.model

/**
 * Pad group on the EP-133 (A–D).
 *
 * Official MIDI note map (teenage.engineering/guides/ep-133/system):
 *   A: notes 36–47 (C2–B2)
 *   B: notes 48–59 (C3–B3)
 *   C: notes 60–71 (C4–B4)
 *   D: notes 72–83 (C5–B5)
 *   Default: receive all channels, send on MIDI channel 1.
 */
enum class PadChannel(val baseNote: Int) {
    A(baseNote = 36),
    B(baseNote = 48),
    C(baseNote = 60),
    D(baseNote = 72);
}

/** A single pad on the EP-133 grid. */
data class Pad(
    val label: String,
    val note: Int,
    val midiChannel: Int = 0,
    val defaultSound: String? = null,
)

/** A sound from the EP-133 factory library. */
data class EP133Sound(
    val number: Int,
    val name: String,
    val category: String,
)

/** Sound category grouping. */
data class SoundCategory(
    val id: String,
    val name: String,
)

/** Musical scale definition. */
data class Scale(
    val id: String,
    val name: String,
    val intervals: List<Int>,
)

/** MIDI device seen by the bridge. */
data class MidiPort(
    val id: String,
    val name: String,
)

/** USB permission request lifecycle. */
enum class PermissionState { UNKNOWN, AWAITING, GRANTED, DENIED }

/** Current device connection state. */
data class DeviceState(
    val connected: Boolean = false,
    val deviceName: String = "",
    val outputPortId: String? = null,
    val inputPorts: List<MidiPort> = emptyList(),
    val outputPorts: List<MidiPort> = emptyList(),
    val permissionState: PermissionState = PermissionState.UNKNOWN,
)

/**
 * EP-133 pad layout — 12 pads per group.
 *
 * Physical layout (3 cols × 4 rows, calculator-style):
 *   [7]  [8]  [9]
 *   [4]  [5]  [6]
 *   [1]  [2]  [3]
 *   [.]  [0]  [ENT]
 *
 * Official MIDI note map (teenage.engineering/guides/ep-133/system):
 *   . =base+0, 0 =base+1, ENT=base+2
 *   1 =base+3, 2 =base+4, 3  =base+5
 *   4 =base+6, 5 =base+7, 6  =base+8
 *   7 =base+9, 8 =base+10, 9 =base+11
 *
 *   A=36–47, B=48–59, C=60–71, D=72–83. All on MIDI ch 1.
 */
object EP133Pads {

    /** Display order (top-left to bottom-right, matching 3×4 hardware grid). */
    private val GRID_ORDER = listOf(
        "7" to 9,  "8" to 10, "9" to 11,
        "4" to 6,  "5" to 7,  "6" to 8,
        "1" to 3,  "2" to 4,  "3" to 5,
        "." to 0,  "0" to 1,  "ENT" to 2,
    )

    /**
     * Pad 0 on groups A & D is special: sends note 60 on a unique MIDI channel.
     *   A0 → ch=6 note=60,  D0 → ch=7 note=60
     * Groups B & C pad 0 are normal (base+1 on ch 0).
     */
    private val SPECIAL_PAD0 = mapOf(
        PadChannel.A to 6,  // A0 → note 60, ch 6
        PadChannel.D to 7,  // D0 → note 60, ch 7
    )

    fun padsForChannel(channel: PadChannel): List<Pad> =
        GRID_ORDER.map { (suffix, offset) ->
            val label = "${channel.name}$suffix"
            val specialCh = SPECIAL_PAD0[channel]
            if (suffix == "0" && specialCh != null) {
                Pad(label, note = 60, midiChannel = specialCh, defaultSound = DEFAULT_DRUM_MAP[label])
            } else {
                Pad(label, note = channel.baseNote + offset, defaultSound = DEFAULT_DRUM_MAP[label])
            }
        }

    /** Detect which group + pad index from an incoming MIDI event. */
    fun resolveIncoming(note: Int, ch: Int): Pair<PadChannel, Int>? {
        // Check special pad 0 first (note 60 on ch 6 = A, ch 7 = D)
        if (note == 60 && ch == 6) {
            val idx = GRID_ORDER.indexOfFirst { it.first == "0" }
            return PadChannel.A to idx
        }
        if (note == 60 && ch == 7) {
            val idx = GRID_ORDER.indexOfFirst { it.first == "0" }
            return PadChannel.D to idx
        }

        // Normal: determine group from note range
        val group = when (note) {
            in 36..47 -> PadChannel.A
            in 48..59 -> PadChannel.B
            in 60..71 -> PadChannel.C
            in 72..83 -> PadChannel.D
            else -> return null
        }
        val offset = note - group.baseNote
        val idx = GRID_ORDER.indexOfFirst { it.second == offset }
        if (idx < 0) return null
        return group to idx
    }

    private val DEFAULT_DRUM_MAP = emptyMap<String, String>()
}

/** All supported musical scales. */
object EP133Scales {
    val ALL: List<Scale> = listOf(
        Scale("major", "Major", listOf(0, 2, 4, 5, 7, 9, 11)),
        Scale("minor", "Minor", listOf(0, 2, 3, 5, 7, 8, 10)),
        Scale("dorian", "Dorian", listOf(0, 2, 3, 5, 7, 9, 10)),
        Scale("phrygian", "Phrygian", listOf(0, 1, 3, 5, 7, 8, 10)),
        Scale("lydian", "Lydian", listOf(0, 2, 4, 6, 7, 9, 11)),
        Scale("mixolydian", "Mixolydian", listOf(0, 2, 4, 5, 7, 9, 10)),
        Scale("locrian", "Locrian", listOf(0, 1, 3, 5, 6, 8, 10)),
        Scale("major_pentatonic", "Major Pentatonic", listOf(0, 2, 4, 7, 9)),
        Scale("minor_pentatonic", "Minor Pentatonic", listOf(0, 3, 5, 7, 10)),
        Scale("blues", "Blues", listOf(0, 3, 5, 6, 7, 10)),
        Scale("chromatic", "Chromatic", listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)),
    )

    val ROOT_NOTES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
}

/** Factory sound library. */
object EP133Sounds {
    val CATEGORIES: List<SoundCategory> = listOf(
        SoundCategory("kicks", "Kicks"),
        SoundCategory("snares", "Snares"),
        SoundCategory("cymbals", "Cymbals & Hats"),
        SoundCategory("percussion", "Percussion"),
        SoundCategory("bass", "Bass"),
        SoundCategory("melodic", "Melodic & Synth"),
        SoundCategory("fx", "ENT"),
        SoundCategory("vocal", "Vocal"),
        SoundCategory("po12", "PO-12 Rhythm"),
        SoundCategory("po14", "PO-14 Sub"),
        SoundCategory("po16", "PO-16 Factory"),
        SoundCategory("po20", "PO-20 Arcade"),
        SoundCategory("po24", "PO-24 Office"),
        SoundCategory("po28", "PO-28 Robot"),
        SoundCategory("po32", "PO-32 Tonic"),
        SoundCategory("po33", "PO-33 K.O!"),
        SoundCategory("po35", "PO-35 Speak"),
        SoundCategory("po128", "PO-128 Mega Man"),
        SoundCategory("po133", "PO-133 Street Fighter"),
        SoundCategory("po137", "PO-137 Rick & Morty"),
    )

    val ALL: List<EP133Sound> = listOf(
        EP133Sound(1, "MICRO KICK", "kicks"),
        EP133Sound(2, "NT KICK", "kicks"),
        EP133Sound(3, "LOCK KIK", "kicks"),
        EP133Sound(4, "BOOMER KICK", "kicks"),
        EP133Sound(5, "KICK 808", "kicks"),
        EP133Sound(6, "KICK 909", "kicks"),
        EP133Sound(7, "KICK DEEP", "kicks"),
        EP133Sound(8, "KICK PUNCH", "kicks"),
        EP133Sound(9, "KICK TIGHT", "kicks"),
        EP133Sound(10, "KICK LO-FI", "kicks"),
        EP133Sound(11, "KICK VINYL", "kicks"),
        EP133Sound(12, "KICK DUSTY", "kicks"),
        EP133Sound(13, "KICK BOOM", "kicks"),
        EP133Sound(14, "KICK SUB", "kicks"),
        EP133Sound(15, "KICK CLICK", "kicks"),
        EP133Sound(16, "KICK ELECTRO", "kicks"),
        EP133Sound(17, "KICK TAPE", "kicks"),
        EP133Sound(18, "KICK GARAGE", "kicks"),
        EP133Sound(19, "KICK TRAP", "kicks"),
        EP133Sound(20, "KICK LAYER", "kicks"),
        EP133Sound(50, "NT SNARE", "snares"),
        EP133Sound(51, "SNARE LO", "snares"),
        EP133Sound(52, "SNARE MID", "snares"),
        EP133Sound(53, "SNARE HI", "snares"),
        EP133Sound(54, "SNARE 808", "snares"),
        EP133Sound(55, "SNARE 909", "snares"),
        EP133Sound(56, "SNARE TIGHT", "snares"),
        EP133Sound(57, "SNARE CRACK", "snares"),
        EP133Sound(58, "SNARE RIM", "snares"),
        EP133Sound(59, "SNARE BRUSH", "snares"),
        EP133Sound(60, "SNARE CLAP", "snares"),
        EP133Sound(61, "SNARE VINYL", "snares"),
        EP133Sound(62, "SNARE TRAP", "snares"),
        EP133Sound(63, "SNARE ROLL", "snares"),
        EP133Sound(64, "RIMSHOT", "snares"),
        EP133Sound(100, "HH CLOSED", "cymbals"),
        EP133Sound(101, "HH CLOSED TIGHT", "cymbals"),
        EP133Sound(102, "HH OPEN", "cymbals"),
        EP133Sound(103, "HH OPEN LONG", "cymbals"),
        EP133Sound(104, "HH PEDAL", "cymbals"),
        EP133Sound(105, "HH 808", "cymbals"),
        EP133Sound(106, "HH 909", "cymbals"),
        EP133Sound(107, "RIDE", "cymbals"),
        EP133Sound(108, "RIDE BELL", "cymbals"),
        EP133Sound(109, "CRASH", "cymbals"),
        EP133Sound(110, "CRASH SHORT", "cymbals"),
        EP133Sound(111, "CYMBAL REV", "cymbals"),
        EP133Sound(112, "CHINA", "cymbals"),
        EP133Sound(150, "CLAP 808", "percussion"),
        EP133Sound(151, "CLAP 909", "percussion"),
        EP133Sound(152, "CLAP SNAP", "percussion"),
        EP133Sound(153, "SNAP FINGER", "percussion"),
        EP133Sound(154, "TAMBOURINE", "percussion"),
        EP133Sound(155, "SHAKER", "percussion"),
        EP133Sound(156, "SHAKER FAST", "percussion"),
        EP133Sound(157, "MARACAS", "percussion"),
        EP133Sound(158, "CONGA HI", "percussion"),
        EP133Sound(159, "CONGA LO", "percussion"),
        EP133Sound(160, "BONGO HI", "percussion"),
        EP133Sound(161, "BONGO LO", "percussion"),
        EP133Sound(162, "COWBELL", "percussion"),
        EP133Sound(163, "WOODBLOCK", "percussion"),
        EP133Sound(164, "AGOGO", "percussion"),
        EP133Sound(165, "TRIANGLE", "percussion"),
        EP133Sound(166, "PERC HIT", "percussion"),
        EP133Sound(167, "PERC METAL", "percussion"),
        EP133Sound(200, "BASS SYN", "bass"),
        EP133Sound(201, "BASS SUB", "bass"),
        EP133Sound(202, "BASS 808", "bass"),
        EP133Sound(203, "BASS ACID", "bass"),
        EP133Sound(204, "BASS REESE", "bass"),
        EP133Sound(205, "BASS WOBBLE", "bass"),
        EP133Sound(206, "BASS PICK", "bass"),
        EP133Sound(207, "BASS FINGER", "bass"),
        EP133Sound(208, "BASS SLAP", "bass"),
        EP133Sound(209, "BASS FM", "bass"),
        EP133Sound(210, "BASS GROWL", "bass"),
        EP133Sound(211, "BASS PLUCK", "bass"),
        EP133Sound(250, "PIANO C4", "melodic"),
        EP133Sound(251, "PIANO CHORD", "melodic"),
        EP133Sound(252, "RHODES", "melodic"),
        EP133Sound(253, "ORGAN", "melodic"),
        EP133Sound(254, "STRINGS", "melodic"),
        EP133Sound(255, "STRINGS SHORT", "melodic"),
        EP133Sound(256, "LEAD SYN", "melodic"),
        EP133Sound(257, "LEAD SAW", "melodic"),
        EP133Sound(258, "LEAD SQUARE", "melodic"),
        EP133Sound(259, "PAD WARM", "melodic"),
        EP133Sound(260, "PAD LUSH", "melodic"),
        EP133Sound(261, "PLUCK SYN", "melodic"),
        EP133Sound(262, "CHORD STAB", "melodic"),
        EP133Sound(263, "BELL", "melodic"),
        EP133Sound(264, "MARIMBA", "melodic"),
        EP133Sound(265, "VIBES", "melodic"),
        EP133Sound(266, "GUITAR CLEAN", "melodic"),
        EP133Sound(267, "GUITAR MUTE", "melodic"),
        EP133Sound(268, "HARP", "melodic"),
        EP133Sound(269, "KALIMBA", "melodic"),
        EP133Sound(300, "RISE FX", "fx"),
        EP133Sound(301, "SWEEP DOWN", "fx"),
        EP133Sound(302, "NOISE BURST", "fx"),
        EP133Sound(303, "VINYL CRACKLE", "fx"),
        EP133Sound(304, "TAPE STOP", "fx"),
        EP133Sound(305, "LASER", "fx"),
        EP133Sound(306, "GLITCH", "fx"),
        EP133Sound(307, "IMPACT", "fx"),
        EP133Sound(308, "RISER", "fx"),
        EP133Sound(309, "DOWNER", "fx"),
        EP133Sound(310, "STATIC", "fx"),
        EP133Sound(311, "WHOOSH", "fx"),
        EP133Sound(350, "VOX CHOP", "vocal"),
        EP133Sound(351, "VOX HIT", "vocal"),
        EP133Sound(352, "VOX YEAH", "vocal"),
        EP133Sound(353, "VOX OH", "vocal"),
        EP133Sound(354, "VOX HEY", "vocal"),
        EP133Sound(355, "VOX SCRATCH", "vocal"),
        // ── Pocket Operator Sound Packs (545 samples) ──
        EP133Sound(501, "PO-12 #1", "po12"), EP133Sound(502, "PO-12 #2", "po12"), EP133Sound(503, "PO-12 #3", "po12"), EP133Sound(504, "PO-12 #4", "po12"), EP133Sound(505, "PO-12 #5", "po12"), EP133Sound(506, "PO-12 #6", "po12"), EP133Sound(507, "PO-12 #7", "po12"), EP133Sound(508, "PO-12 #8", "po12"), EP133Sound(509, "PO-12 #9", "po12"), EP133Sound(510, "PO-12 #10", "po12"), EP133Sound(511, "PO-12 #11", "po12"), EP133Sound(512, "PO-12 #12", "po12"), EP133Sound(513, "PO-12 #13", "po12"), EP133Sound(514, "PO-12 #14", "po12"), EP133Sound(515, "PO-12 #15", "po12"), EP133Sound(516, "PO-12 #16", "po12"), EP133Sound(517, "PO-12 #17", "po12"), EP133Sound(518, "PO-12 #18", "po12"), EP133Sound(519, "PO-12 #19", "po12"), EP133Sound(520, "PO-12 #20", "po12"), EP133Sound(521, "PO-12 #21", "po12"), EP133Sound(522, "PO-12 #22", "po12"), EP133Sound(523, "PO-12 #23", "po12"), EP133Sound(524, "PO-12 #24", "po12"),
        EP133Sound(525, "PO-14 #1", "po14"), EP133Sound(526, "PO-14 #2", "po14"), EP133Sound(527, "PO-14 #3", "po14"), EP133Sound(528, "PO-14 #4", "po14"), EP133Sound(529, "PO-14 #5", "po14"), EP133Sound(530, "PO-14 #6", "po14"), EP133Sound(531, "PO-14 #7", "po14"), EP133Sound(532, "PO-14 #8", "po14"), EP133Sound(533, "PO-14 #9", "po14"), EP133Sound(534, "PO-14 #10", "po14"), EP133Sound(535, "PO-14 #11", "po14"), EP133Sound(536, "PO-14 #12", "po14"), EP133Sound(537, "PO-14 #13", "po14"), EP133Sound(538, "PO-14 #14", "po14"), EP133Sound(539, "PO-14 #15", "po14"), EP133Sound(540, "PO-14 #16", "po14"), EP133Sound(541, "PO-14 #17", "po14"), EP133Sound(542, "PO-14 #18", "po14"), EP133Sound(543, "PO-14 #19", "po14"), EP133Sound(544, "PO-14 #20", "po14"), EP133Sound(545, "PO-14 #21", "po14"), EP133Sound(546, "PO-14 #22", "po14"), EP133Sound(547, "PO-14 #23", "po14"), EP133Sound(548, "PO-14 #24", "po14"), EP133Sound(549, "PO-14 #25", "po14"), EP133Sound(550, "PO-14 #26", "po14"), EP133Sound(551, "PO-14 #27", "po14"), EP133Sound(552, "PO-14 #28", "po14"), EP133Sound(553, "PO-14 #29", "po14"), EP133Sound(554, "PO-14 #30", "po14"), EP133Sound(555, "PO-14 #31", "po14"), EP133Sound(556, "PO-14 #32", "po14"), EP133Sound(557, "PO-14 #33", "po14"), EP133Sound(558, "PO-14 #34", "po14"), EP133Sound(559, "PO-14 #35", "po14"), EP133Sound(560, "PO-14 #36", "po14"), EP133Sound(561, "PO-14 #37", "po14"), EP133Sound(562, "PO-14 #38", "po14"), EP133Sound(563, "PO-14 #39", "po14"), EP133Sound(564, "PO-14 #40", "po14"), EP133Sound(565, "PO-14 #41", "po14"), EP133Sound(566, "PO-14 #42", "po14"), EP133Sound(567, "PO-14 #43", "po14"), EP133Sound(568, "PO-14 #44", "po14"), EP133Sound(569, "PO-14 #45", "po14"), EP133Sound(570, "PO-14 #46", "po14"), EP133Sound(571, "PO-14 #47", "po14"), EP133Sound(572, "PO-14 #48", "po14"),
        EP133Sound(573, "PO-16 #1", "po16"), EP133Sound(574, "PO-16 #2", "po16"), EP133Sound(575, "PO-16 #3", "po16"), EP133Sound(576, "PO-16 #4", "po16"), EP133Sound(577, "PO-16 #5", "po16"), EP133Sound(578, "PO-16 #6", "po16"), EP133Sound(579, "PO-16 #7", "po16"), EP133Sound(580, "PO-16 #8", "po16"), EP133Sound(581, "PO-16 #9", "po16"), EP133Sound(582, "PO-16 #10", "po16"), EP133Sound(583, "PO-16 #11", "po16"), EP133Sound(584, "PO-16 #12", "po16"), EP133Sound(585, "PO-16 #13", "po16"), EP133Sound(586, "PO-16 #14", "po16"), EP133Sound(587, "PO-16 #15", "po16"), EP133Sound(588, "PO-16 #16", "po16"), EP133Sound(589, "PO-16 #17", "po16"), EP133Sound(590, "PO-16 #18", "po16"), EP133Sound(591, "PO-16 #19", "po16"), EP133Sound(592, "PO-16 #20", "po16"), EP133Sound(593, "PO-16 #21", "po16"), EP133Sound(594, "PO-16 #22", "po16"), EP133Sound(595, "PO-16 #23", "po16"), EP133Sound(596, "PO-16 #24", "po16"), EP133Sound(597, "PO-16 #25", "po16"), EP133Sound(598, "PO-16 #26", "po16"), EP133Sound(599, "PO-16 #27", "po16"), EP133Sound(600, "PO-16 #28", "po16"), EP133Sound(601, "PO-16 #29", "po16"), EP133Sound(602, "PO-16 #30", "po16"), EP133Sound(603, "PO-16 #31", "po16"), EP133Sound(604, "PO-16 #32", "po16"), EP133Sound(605, "PO-16 #33", "po16"), EP133Sound(606, "PO-16 #34", "po16"), EP133Sound(607, "PO-16 #35", "po16"), EP133Sound(608, "PO-16 #36", "po16"), EP133Sound(609, "PO-16 #37", "po16"), EP133Sound(610, "PO-16 #38", "po16"), EP133Sound(611, "PO-16 #39", "po16"), EP133Sound(612, "PO-16 #40", "po16"), EP133Sound(613, "PO-16 #41", "po16"), EP133Sound(614, "PO-16 #42", "po16"), EP133Sound(615, "PO-16 #43", "po16"), EP133Sound(616, "PO-16 #44", "po16"), EP133Sound(617, "PO-16 #45", "po16"), EP133Sound(618, "PO-16 #46", "po16"), EP133Sound(619, "PO-16 #47", "po16"), EP133Sound(620, "PO-16 #48", "po16"),
        EP133Sound(621, "PO-20 #1", "po20"), EP133Sound(622, "PO-20 #2", "po20"), EP133Sound(623, "PO-20 #3", "po20"), EP133Sound(624, "PO-20 #4", "po20"), EP133Sound(625, "PO-20 #5", "po20"), EP133Sound(626, "PO-20 #6", "po20"), EP133Sound(627, "PO-20 #7", "po20"), EP133Sound(628, "PO-20 #8", "po20"), EP133Sound(629, "PO-20 #9", "po20"), EP133Sound(630, "PO-20 #10", "po20"), EP133Sound(631, "PO-20 #11", "po20"), EP133Sound(632, "PO-20 #12", "po20"), EP133Sound(633, "PO-20 #13", "po20"), EP133Sound(634, "PO-20 #14", "po20"), EP133Sound(635, "PO-20 #15", "po20"), EP133Sound(636, "PO-20 #16", "po20"), EP133Sound(637, "PO-20 #17", "po20"), EP133Sound(638, "PO-20 #18", "po20"), EP133Sound(639, "PO-20 #19", "po20"), EP133Sound(640, "PO-20 #20", "po20"), EP133Sound(641, "PO-20 #21", "po20"),
        EP133Sound(642, "PO-24 #1", "po24"), EP133Sound(643, "PO-24 #2", "po24"), EP133Sound(644, "PO-24 #3", "po24"), EP133Sound(645, "PO-24 #4", "po24"), EP133Sound(646, "PO-24 #5", "po24"), EP133Sound(647, "PO-24 #6", "po24"), EP133Sound(648, "PO-24 #7", "po24"), EP133Sound(649, "PO-24 #8", "po24"), EP133Sound(650, "PO-24 #9", "po24"), EP133Sound(651, "PO-24 #10", "po24"), EP133Sound(652, "PO-24 #11", "po24"), EP133Sound(653, "PO-24 #12", "po24"), EP133Sound(654, "PO-24 #13", "po24"), EP133Sound(655, "PO-24 #14", "po24"), EP133Sound(656, "PO-24 #15", "po24"), EP133Sound(657, "PO-24 #16", "po24"), EP133Sound(658, "PO-24 #17", "po24"), EP133Sound(659, "PO-24 #18", "po24"), EP133Sound(660, "PO-24 #19", "po24"),
        EP133Sound(661, "PO-28 #1", "po28"), EP133Sound(662, "PO-28 #2", "po28"), EP133Sound(663, "PO-28 #3", "po28"), EP133Sound(664, "PO-28 #4", "po28"), EP133Sound(665, "PO-28 #5", "po28"), EP133Sound(666, "PO-28 #6", "po28"), EP133Sound(667, "PO-28 #7", "po28"), EP133Sound(668, "PO-28 #8", "po28"), EP133Sound(669, "PO-28 #9", "po28"), EP133Sound(670, "PO-28 #10", "po28"), EP133Sound(671, "PO-28 #11", "po28"), EP133Sound(672, "PO-28 #12", "po28"), EP133Sound(673, "PO-28 #13", "po28"), EP133Sound(674, "PO-28 #14", "po28"), EP133Sound(675, "PO-28 #15", "po28"), EP133Sound(676, "PO-28 #16", "po28"), EP133Sound(677, "PO-28 #17", "po28"), EP133Sound(678, "PO-28 #18", "po28"), EP133Sound(679, "PO-28 #19", "po28"), EP133Sound(680, "PO-28 #20", "po28"), EP133Sound(681, "PO-28 #21", "po28"), EP133Sound(682, "PO-28 #22", "po28"), EP133Sound(683, "PO-28 #23", "po28"), EP133Sound(684, "PO-28 #24", "po28"), EP133Sound(685, "PO-28 #25", "po28"), EP133Sound(686, "PO-28 #26", "po28"), EP133Sound(687, "PO-28 #27", "po28"), EP133Sound(688, "PO-28 #28", "po28"), EP133Sound(689, "PO-28 #29", "po28"), EP133Sound(690, "PO-28 #30", "po28"), EP133Sound(691, "PO-28 #31", "po28"),
        EP133Sound(692, "PO-32 #1", "po32"), EP133Sound(693, "PO-32 #2", "po32"), EP133Sound(694, "PO-32 #3", "po32"), EP133Sound(695, "PO-32 #4", "po32"), EP133Sound(696, "PO-32 #5", "po32"), EP133Sound(697, "PO-32 #6", "po32"), EP133Sound(698, "PO-32 #7", "po32"), EP133Sound(699, "PO-32 #8", "po32"), EP133Sound(700, "PO-32 #9", "po32"), EP133Sound(701, "PO-32 #10", "po32"), EP133Sound(702, "PO-32 #11", "po32"), EP133Sound(703, "PO-32 #12", "po32"), EP133Sound(704, "PO-32 #13", "po32"), EP133Sound(705, "PO-32 #14", "po32"), EP133Sound(706, "PO-32 #15", "po32"), EP133Sound(707, "PO-32 #16", "po32"),
        EP133Sound(708, "PO-33 #1", "po33"), EP133Sound(709, "PO-33 #2", "po33"), EP133Sound(710, "PO-33 #3", "po33"), EP133Sound(711, "PO-33 #4", "po33"), EP133Sound(712, "PO-33 #5", "po33"), EP133Sound(713, "PO-33 #6", "po33"), EP133Sound(714, "PO-33 #7", "po33"), EP133Sound(715, "PO-33 #8", "po33"), EP133Sound(716, "PO-33 #9", "po33"), EP133Sound(717, "PO-33 #10", "po33"), EP133Sound(718, "PO-33 #11", "po33"), EP133Sound(719, "PO-33 #12", "po33"), EP133Sound(720, "PO-33 #13", "po33"), EP133Sound(721, "PO-33 #14", "po33"), EP133Sound(722, "PO-33 #15", "po33"), EP133Sound(723, "PO-33 #16", "po33"), EP133Sound(724, "PO-33 #17", "po33"), EP133Sound(725, "PO-33 #18", "po33"), EP133Sound(726, "PO-33 #19", "po33"), EP133Sound(727, "PO-33 #20", "po33"), EP133Sound(728, "PO-33 #21", "po33"), EP133Sound(729, "PO-33 #22", "po33"), EP133Sound(730, "PO-33 #23", "po33"), EP133Sound(731, "PO-33 #24", "po33"), EP133Sound(732, "PO-33 #25", "po33"), EP133Sound(733, "PO-33 #26", "po33"), EP133Sound(734, "PO-33 #27", "po33"), EP133Sound(735, "PO-33 #28", "po33"), EP133Sound(736, "PO-33 #29", "po33"), EP133Sound(737, "PO-33 #30", "po33"), EP133Sound(738, "PO-33 #31", "po33"), EP133Sound(739, "PO-33 #32", "po33"), EP133Sound(740, "PO-33 #33", "po33"), EP133Sound(741, "PO-33 #34", "po33"), EP133Sound(742, "PO-33 #35", "po33"), EP133Sound(743, "PO-33 #36", "po33"), EP133Sound(744, "PO-33 #37", "po33"), EP133Sound(745, "PO-33 #38", "po33"), EP133Sound(746, "PO-33 #39", "po33"), EP133Sound(747, "PO-33 #40", "po33"), EP133Sound(748, "PO-33 #41", "po33"), EP133Sound(749, "PO-33 #42", "po33"), EP133Sound(750, "PO-33 #43", "po33"), EP133Sound(751, "PO-33 #44", "po33"), EP133Sound(752, "PO-33 #45", "po33"), EP133Sound(753, "PO-33 #46", "po33"), EP133Sound(754, "PO-33 #47", "po33"), EP133Sound(755, "PO-33 #48", "po33"), EP133Sound(756, "PO-33 #49", "po33"), EP133Sound(757, "PO-33 #50", "po33"), EP133Sound(758, "PO-33 #51", "po33"), EP133Sound(759, "PO-33 #52", "po33"), EP133Sound(760, "PO-33 #53", "po33"), EP133Sound(761, "PO-33 #54", "po33"), EP133Sound(762, "PO-33 #55", "po33"), EP133Sound(763, "PO-33 #56", "po33"), EP133Sound(764, "PO-33 #57", "po33"), EP133Sound(765, "PO-33 #58", "po33"), EP133Sound(766, "PO-33 #59", "po33"), EP133Sound(767, "PO-33 #60", "po33"), EP133Sound(768, "PO-33 #61", "po33"), EP133Sound(769, "PO-33 #62", "po33"), EP133Sound(770, "PO-33 #63", "po33"), EP133Sound(771, "PO-33 #64", "po33"), EP133Sound(772, "PO-33 #65", "po33"), EP133Sound(773, "PO-33 #66", "po33"), EP133Sound(774, "PO-33 #67", "po33"), EP133Sound(775, "PO-33 #68", "po33"),
        EP133Sound(776, "PO-35 #1", "po35"), EP133Sound(777, "PO-35 #2", "po35"), EP133Sound(778, "PO-35 #3", "po35"), EP133Sound(779, "PO-35 #4", "po35"), EP133Sound(780, "PO-35 #5", "po35"), EP133Sound(781, "PO-35 #6", "po35"), EP133Sound(782, "PO-35 #7", "po35"), EP133Sound(783, "PO-35 #8", "po35"), EP133Sound(784, "PO-35 #9", "po35"), EP133Sound(785, "PO-35 #10", "po35"), EP133Sound(786, "PO-35 #11", "po35"), EP133Sound(787, "PO-35 #12", "po35"), EP133Sound(788, "PO-35 #13", "po35"), EP133Sound(789, "PO-35 #14", "po35"), EP133Sound(790, "PO-35 #15", "po35"), EP133Sound(791, "PO-35 #16", "po35"), EP133Sound(792, "PO-35 #17", "po35"), EP133Sound(793, "PO-35 #18", "po35"), EP133Sound(794, "PO-35 #19", "po35"), EP133Sound(795, "PO-35 #20", "po35"), EP133Sound(796, "PO-35 #21", "po35"), EP133Sound(797, "PO-35 #22", "po35"), EP133Sound(798, "PO-35 #23", "po35"), EP133Sound(799, "PO-35 #24", "po35"), EP133Sound(800, "PO-35 #25", "po35"), EP133Sound(801, "PO-35 #26", "po35"), EP133Sound(802, "PO-35 #27", "po35"), EP133Sound(803, "PO-35 #28", "po35"), EP133Sound(804, "PO-35 #29", "po35"), EP133Sound(805, "PO-35 #30", "po35"), EP133Sound(806, "PO-35 #31", "po35"),
        EP133Sound(807, "PO-128 #1", "po128"), EP133Sound(808, "PO-128 #2", "po128"), EP133Sound(809, "PO-128 #3", "po128"), EP133Sound(810, "PO-128 #4", "po128"), EP133Sound(811, "PO-128 #5", "po128"), EP133Sound(812, "PO-128 #6", "po128"), EP133Sound(813, "PO-128 #7", "po128"), EP133Sound(814, "PO-128 #8", "po128"), EP133Sound(815, "PO-128 #9", "po128"), EP133Sound(816, "PO-128 #10", "po128"), EP133Sound(817, "PO-128 #11", "po128"), EP133Sound(818, "PO-128 #12", "po128"), EP133Sound(819, "PO-128 #13", "po128"), EP133Sound(820, "PO-128 #14", "po128"), EP133Sound(821, "PO-128 #15", "po128"), EP133Sound(822, "PO-128 #16", "po128"), EP133Sound(823, "PO-128 #17", "po128"), EP133Sound(824, "PO-128 #18", "po128"), EP133Sound(825, "PO-128 #19", "po128"), EP133Sound(826, "PO-128 #20", "po128"), EP133Sound(827, "PO-128 #21", "po128"), EP133Sound(828, "PO-128 #22", "po128"), EP133Sound(829, "PO-128 #23", "po128"), EP133Sound(830, "PO-128 #24", "po128"), EP133Sound(831, "PO-128 #25", "po128"), EP133Sound(832, "PO-128 #26", "po128"), EP133Sound(833, "PO-128 #27", "po128"), EP133Sound(834, "PO-128 #28", "po128"), EP133Sound(835, "PO-128 #29", "po128"), EP133Sound(836, "PO-128 #30", "po128"), EP133Sound(837, "PO-128 #31", "po128"), EP133Sound(838, "PO-128 #32", "po128"), EP133Sound(839, "PO-128 #33", "po128"), EP133Sound(840, "PO-128 #34", "po128"), EP133Sound(841, "PO-128 #35", "po128"), EP133Sound(842, "PO-128 #36", "po128"), EP133Sound(843, "PO-128 #37", "po128"), EP133Sound(844, "PO-128 #38", "po128"), EP133Sound(845, "PO-128 #39", "po128"), EP133Sound(846, "PO-128 #40", "po128"), EP133Sound(847, "PO-128 #41", "po128"), EP133Sound(848, "PO-128 #42", "po128"), EP133Sound(849, "PO-128 #43", "po128"), EP133Sound(850, "PO-128 #44", "po128"), EP133Sound(851, "PO-128 #45", "po128"), EP133Sound(852, "PO-128 #46", "po128"), EP133Sound(853, "PO-128 #47", "po128"), EP133Sound(854, "PO-128 #48", "po128"), EP133Sound(855, "PO-128 #49", "po128"), EP133Sound(856, "PO-128 #50", "po128"),
        EP133Sound(857, "PO-128 #51", "po128"), EP133Sound(858, "PO-128 #52", "po128"), EP133Sound(859, "PO-128 #53", "po128"), EP133Sound(860, "PO-128 #54", "po128"), EP133Sound(861, "PO-128 #55", "po128"), EP133Sound(862, "PO-128 #56", "po128"), EP133Sound(863, "PO-128 #57", "po128"), EP133Sound(864, "PO-128 #58", "po128"), EP133Sound(865, "PO-128 #59", "po128"), EP133Sound(866, "PO-128 #60", "po128"), EP133Sound(867, "PO-128 #61", "po128"), EP133Sound(868, "PO-128 #62", "po128"), EP133Sound(869, "PO-128 #63", "po128"), EP133Sound(870, "PO-128 #64", "po128"), EP133Sound(871, "PO-128 #65", "po128"), EP133Sound(872, "PO-128 #66", "po128"), EP133Sound(873, "PO-128 #67", "po128"), EP133Sound(874, "PO-128 #68", "po128"), EP133Sound(875, "PO-128 #69", "po128"), EP133Sound(876, "PO-128 #70", "po128"), EP133Sound(877, "PO-128 #71", "po128"), EP133Sound(878, "PO-128 #72", "po128"), EP133Sound(879, "PO-128 #73", "po128"), EP133Sound(880, "PO-128 #74", "po128"), EP133Sound(881, "PO-128 #75", "po128"), EP133Sound(882, "PO-128 #76", "po128"), EP133Sound(883, "PO-128 #77", "po128"), EP133Sound(884, "PO-128 #78", "po128"), EP133Sound(885, "PO-128 #79", "po128"), EP133Sound(886, "PO-128 #80", "po128"), EP133Sound(887, "PO-128 #81", "po128"), EP133Sound(888, "PO-128 #82", "po128"), EP133Sound(889, "PO-128 #83", "po128"), EP133Sound(890, "PO-128 #84", "po128"), EP133Sound(891, "PO-128 #85", "po128"), EP133Sound(892, "PO-128 #86", "po128"), EP133Sound(893, "PO-128 #87", "po128"), EP133Sound(894, "PO-128 #88", "po128"), EP133Sound(895, "PO-128 #89", "po128"), EP133Sound(896, "PO-128 #90", "po128"), EP133Sound(897, "PO-128 #91", "po128"), EP133Sound(898, "PO-128 #92", "po128"), EP133Sound(899, "PO-128 #93", "po128"), EP133Sound(900, "PO-128 #94", "po128"), EP133Sound(901, "PO-128 #95", "po128"), EP133Sound(902, "PO-128 #96", "po128"), EP133Sound(903, "PO-128 #97", "po128"), EP133Sound(904, "PO-128 #98", "po128"), EP133Sound(905, "PO-128 #99", "po128"), EP133Sound(906, "PO-128 #100", "po128"),
        EP133Sound(907, "PO-128 #101", "po128"), EP133Sound(908, "PO-128 #102", "po128"), EP133Sound(909, "PO-128 #103", "po128"), EP133Sound(910, "PO-128 #104", "po128"), EP133Sound(911, "PO-128 #105", "po128"), EP133Sound(912, "PO-128 #106", "po128"), EP133Sound(913, "PO-128 #107", "po128"), EP133Sound(914, "PO-128 #108", "po128"), EP133Sound(915, "PO-128 #109", "po128"), EP133Sound(916, "PO-128 #110", "po128"), EP133Sound(917, "PO-128 #111", "po128"), EP133Sound(918, "PO-128 #112", "po128"), EP133Sound(919, "PO-128 #113", "po128"), EP133Sound(920, "PO-128 #114", "po128"), EP133Sound(921, "PO-128 #115", "po128"), EP133Sound(922, "PO-128 #116", "po128"), EP133Sound(923, "PO-128 #117", "po128"), EP133Sound(924, "PO-128 #118", "po128"), EP133Sound(925, "PO-128 #119", "po128"), EP133Sound(926, "PO-128 #120", "po128"), EP133Sound(927, "PO-128 #121", "po128"), EP133Sound(928, "PO-128 #122", "po128"), EP133Sound(929, "PO-128 #123", "po128"), EP133Sound(930, "PO-128 #124", "po128"), EP133Sound(931, "PO-128 #125", "po128"), EP133Sound(932, "PO-128 #126", "po128"), EP133Sound(933, "PO-128 #127", "po128"), EP133Sound(934, "PO-128 #128", "po128"), EP133Sound(935, "PO-128 #129", "po128"), EP133Sound(936, "PO-128 #130", "po128"), EP133Sound(937, "PO-128 #131", "po128"), EP133Sound(938, "PO-128 #132", "po128"), EP133Sound(939, "PO-128 #133", "po128"), EP133Sound(940, "PO-128 #134", "po128"), EP133Sound(941, "PO-128 #135", "po128"), EP133Sound(942, "PO-128 #136", "po128"), EP133Sound(943, "PO-128 #137", "po128"), EP133Sound(944, "PO-128 #138", "po128"), EP133Sound(945, "PO-128 #139", "po128"), EP133Sound(946, "PO-128 #140", "po128"), EP133Sound(947, "PO-128 #141", "po128"), EP133Sound(948, "PO-128 #142", "po128"), EP133Sound(949, "PO-128 #143", "po128"), EP133Sound(950, "PO-128 #144", "po128"), EP133Sound(951, "PO-128 #145", "po128"), EP133Sound(952, "PO-128 #146", "po128"), EP133Sound(953, "PO-128 #147", "po128"), EP133Sound(954, "PO-128 #148", "po128"), EP133Sound(955, "PO-128 #149", "po128"), EP133Sound(956, "PO-128 #150", "po128"), EP133Sound(957, "PO-128 #151", "po128"), EP133Sound(958, "PO-128 #152", "po128"), EP133Sound(959, "PO-128 #153", "po128"), EP133Sound(960, "PO-128 #154", "po128"), EP133Sound(961, "PO-128 #155", "po128"), EP133Sound(962, "PO-128 #156", "po128"), EP133Sound(963, "PO-128 #157", "po128"),
        EP133Sound(964, "PO-133 #1", "po133"), EP133Sound(965, "PO-133 #2", "po133"), EP133Sound(966, "PO-133 #3", "po133"), EP133Sound(967, "PO-133 #4", "po133"), EP133Sound(968, "PO-133 #5", "po133"), EP133Sound(969, "PO-133 #6", "po133"), EP133Sound(970, "PO-133 #7", "po133"), EP133Sound(971, "PO-133 #8", "po133"), EP133Sound(972, "PO-133 #9", "po133"), EP133Sound(973, "PO-133 #10", "po133"), EP133Sound(974, "PO-133 #11", "po133"), EP133Sound(975, "PO-133 #12", "po133"), EP133Sound(976, "PO-133 #13", "po133"), EP133Sound(977, "PO-133 #14", "po133"), EP133Sound(978, "PO-133 #15", "po133"), EP133Sound(979, "PO-133 #16", "po133"), EP133Sound(980, "PO-133 #17", "po133"), EP133Sound(981, "PO-133 #18", "po133"), EP133Sound(982, "PO-133 #19", "po133"), EP133Sound(983, "PO-133 #20", "po133"), EP133Sound(984, "PO-133 #21", "po133"), EP133Sound(985, "PO-133 #22", "po133"), EP133Sound(986, "PO-133 #23", "po133"), EP133Sound(987, "PO-133 #24", "po133"), EP133Sound(988, "PO-133 #25", "po133"), EP133Sound(989, "PO-133 #26", "po133"), EP133Sound(990, "PO-133 #27", "po133"), EP133Sound(991, "PO-133 #28", "po133"), EP133Sound(992, "PO-133 #29", "po133"), EP133Sound(993, "PO-133 #30", "po133"), EP133Sound(994, "PO-133 #31", "po133"), EP133Sound(995, "PO-133 #32", "po133"), EP133Sound(996, "PO-133 #33", "po133"), EP133Sound(997, "PO-133 #34", "po133"), EP133Sound(998, "PO-133 #35", "po133"), EP133Sound(999, "PO-133 #36", "po133"), EP133Sound(1000, "PO-133 #37", "po133"), EP133Sound(1001, "PO-133 #38", "po133"), EP133Sound(1002, "PO-133 #39", "po133"), EP133Sound(1003, "PO-133 #40", "po133"), EP133Sound(1004, "PO-133 #41", "po133"), EP133Sound(1005, "PO-133 #42", "po133"), EP133Sound(1006, "PO-133 #43", "po133"), EP133Sound(1007, "PO-133 #44", "po133"), EP133Sound(1008, "PO-133 #45", "po133"), EP133Sound(1009, "PO-133 #46", "po133"), EP133Sound(1010, "PO-133 #47", "po133"), EP133Sound(1011, "PO-133 #48", "po133"), EP133Sound(1012, "PO-133 #49", "po133"), EP133Sound(1013, "PO-133 #50", "po133"), EP133Sound(1014, "PO-133 #51", "po133"), EP133Sound(1015, "PO-133 #52", "po133"),
        EP133Sound(1016, "PO-137 #1", "po137"), EP133Sound(1017, "PO-137 #2", "po137"), EP133Sound(1018, "PO-137 #3", "po137"), EP133Sound(1019, "PO-137 #4", "po137"), EP133Sound(1020, "PO-137 #5", "po137"), EP133Sound(1021, "PO-137 #6", "po137"), EP133Sound(1022, "PO-137 #7", "po137"), EP133Sound(1023, "PO-137 #8", "po137"), EP133Sound(1024, "PO-137 #9", "po137"), EP133Sound(1025, "PO-137 #10", "po137"), EP133Sound(1026, "PO-137 #11", "po137"), EP133Sound(1027, "PO-137 #12", "po137"), EP133Sound(1028, "PO-137 #13", "po137"), EP133Sound(1029, "PO-137 #14", "po137"), EP133Sound(1030, "PO-137 #15", "po137"), EP133Sound(1031, "PO-137 #16", "po137"), EP133Sound(1032, "PO-137 #17", "po137"), EP133Sound(1033, "PO-137 #18", "po137"), EP133Sound(1034, "PO-137 #19", "po137"), EP133Sound(1035, "PO-137 #20", "po137"), EP133Sound(1036, "PO-137 #21", "po137"), EP133Sound(1037, "PO-137 #22", "po137"), EP133Sound(1038, "PO-137 #23", "po137"), EP133Sound(1039, "PO-137 #24", "po137"), EP133Sound(1040, "PO-137 #25", "po137"), EP133Sound(1041, "PO-137 #26", "po137"), EP133Sound(1042, "PO-137 #27", "po137"), EP133Sound(1043, "PO-137 #28", "po137"), EP133Sound(1044, "PO-137 #29", "po137"), EP133Sound(1045, "PO-137 #30", "po137"),
    )
}
